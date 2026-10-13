// AI社員の完了・失敗の報告が、話している間に届いても消えないことの回帰テスト（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 約3分半で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、音声認識・読み上げ・司令本部の巡回（/api/command-center）・AI将軍への送信（/api/ai/command）を、
// このページの中だけで偽物に差し替える。OpenAI は呼ばれず、DB にも書き込まない。ほかの読み込み（GET）は普段どおり動く。
// 読み上げは偽物なので、実際に聞こえるかは別に確かめること。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const waitFor = async (condition, limitMs = 3000) => {
        for (let waited = 0; waited < limitMs && !condition(); waited += 50) await wait(50);
        return condition();
    };
    const results = [];
    const log = [];
    const t0 = performance.now();
    const note = text => log.push(`${Math.round(performance.now() - t0)}ms ${text}`);

    // 読み上げ：mode が "normal" なら少しして始まり、holdEnd でなければ200ms後に終わる。
    // "error" は始まらずに失敗を通知する。"never" は何も通知しない（固まった場合）。
    const synth = window.speechSynthesis;
    // history は読み上げを頼まれた順の記録（遅れて届く通知を、あとからその読み上げに送るため）。
    const speech = { mode: "normal", holdEnd: false, current: null, starts: [], history: [], cancels: 0 };
    Object.defineProperty(synth, "speaking", { configurable: true, get: () => Boolean(speech.current?.started) });
    Object.defineProperty(synth, "pending", { configurable: true, get: () => Boolean(speech.current && !speech.current.started) });
    const finishSpeech = () => {
        const current = speech.current;
        if (!current) return;
        speech.current = null;
        note(`読み上げ終了「${current.utterance.text}」`);
        current.utterance.onend?.({});
    };
    synth.speak = utterance => {
        const current = { utterance, started: false };
        speech.current = current;
        speech.history.push(utterance);
        note(`読み上げ依頼「${utterance.text}」（${speech.mode}）`);
        if (speech.mode === "never") return;
        setTimeout(() => {
            if (speech.current !== current) return;
            if (speech.mode === "error") {
                speech.current = null;
                note("読み上げを始められなかった");
                utterance.onerror?.({ error: "synthesis-failed" });
                return;
            }
            current.started = true;
            speech.starts.push(utterance.text);
            note(`読み上げ開始「${utterance.text}」`);
            utterance.onstart?.({});
            if (!speech.holdEnd) setTimeout(() => { if (speech.current === current) finishSpeech(); }, 200);
        }, 50);
    };
    synth.cancel = () => {
        speech.cancels++;
        const current = speech.current;
        if (!current) return;
        speech.current = null;
        note(`読み上げ取り消し「${current.utterance.text}」`);
        setTimeout(() => current.utterance.onerror?.({ error: current.started ? "interrupted" : "canceled" }), 10);
    };

    // 聞き取り：始まったら、こちらが終わらせるまで聞き取り中のまま（発言なし）。
    const proto = (window.SpeechRecognition || window.webkitSpeechRecognition).prototype;
    let heldRecognition = null;
    proto.start = function () {
        heldRecognition = this;
        this.__stopped = false;
        note(`聞き取り開始（読み上げ中: ${synth.speaking || synth.pending}）`);
        setTimeout(() => this.onstart?.({}), 30);
    };
    proto.stop = function () { this.__stopped = true; setTimeout(() => this.onend?.({}), 20); };
    proto.abort = function () { this.__stopped = true; setTimeout(() => { this.onerror?.({ error: "aborted" }); this.onend?.({}); }, 20); };
    const endListeningSilently = () => { const r = heldRecognition; heldRecognition = null; r?.onend?.({}); };

    // 司令本部の巡回：rows（新しい順）を実行ログとして返す。ほかの項目は「取得できません」。
    let rows = [];
    let nextId = 900000;
    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
        const path = new URL(typeof input === "string" ? input : input.url, location.href).pathname;
        if (path === "/api/command-center") {
            return new Response(JSON.stringify({ numbers: null, money: null, priorityTasks: null, agents: null,
                activities: rows.slice(0, 20) }), { headers: { "Content-Type": "application/json" } });
        }
        if (path === "/api/ai/command") {
            note(`AI将軍への送信「${JSON.parse(init.body).message}」（テストでは送らない）`);
            return new Response("テストでは送らない", { status: 503 });
        }
        return realFetch(input, init);
    };
    window.alert = message => note(`alert: ${message}`);

    const activity = (agent, action, status, detail, seconds = "13") =>
        ({ id: String(nextId++), agent, action, status, detail, startedAt: "2026-10-13 10:00:00.0", durationSeconds: seconds });
    const quality = (status, detail) => activity("品質管理AI", "公開前の品質を採点する", status, detail);
    const FAILED = "品質管理AIの「公開前の品質を採点する」が失敗しました。WAI-FORMAT";
    const DONE = "営業AIが「販売戦略を作る」を13秒で終えました。完了";
    // 届いた記録を巡回1回で受け取る。
    const deliver = async (...arrived) => {
        rows = [...arrived.reverse(), ...rows];
        await loadCommandCenter();
    };
    const poll = async times => {
        for (let i = 0; i < times; i++) await loadCommandCenter();
    };

    // 試験ごとに、何も話していない待機中と、読み込み直後の状態に戻す。
    const reset = async () => {
        if (voiceSessionActive) endVoiceSession(null);
        if (heldRecognition) endListeningSilently();
        if (handsFreeStream) disableHandsFree();
        speech.mode = "normal";
        speech.holdEnd = false;
        speech.current = null;
        await wait(3000);
        // 修正後の版にだけある変数。修正前の版でも同じテストを流せるよう、あれば初期化する。
        if (typeof pendingReports !== "undefined") {
            pendingReports = [];
            reportRetryAt = 0;
            reportSpeaking = false;
            reportedActivityIds = null;
            if (typeof reportStartFailures !== "undefined") reportStartFailures = 0;
        } else {
            lastSeenActivityId = null;
        }
        rows = [activity("ライターAI", "記事を書く", "完了", "読み込み前の完了")];
        await loadCommandCenter();
        setVoiceState("idle");
        await wait(100);
        speech.starts = [];
    };
    const check = (name, actual, expected) => {
        const ok = JSON.stringify(actual) === JSON.stringify(expected);
        results.push({ 試験: name, 判定: ok ? "合格" : "不合格", 実際: JSON.stringify(actual), 期待: JSON.stringify(expected) });
    };
    const run = async (name, body) => {
        await reset();
        note(`--- ${name}`);
        try {
            await body();
        } catch (error) {
            results.push({ 試験: name, 判定: "不合格", 実際: String(error), 期待: "" });
        }
    };

    await run("1 待機中の失敗は、すぐ1回だけ読む", async () => {
        await deliver(quality("エラー", "WAI-FORMAT"));
        await waitFor(() => speech.starts.length > 0);
        await poll(6);
        await wait(500);
        check("1 待機中の失敗", speech.starts, [FAILED]);
    });

    await run("2 聞き取り中に届いた失敗は、待機に戻ってから1回だけ読む", async () => {
        startListening();
        await wait(100);
        await deliver(quality("エラー", "WAI-FORMAT"));
        await wait(300);
        check("2 聞き取り中は読まない", speech.starts, []);
        endListeningSilently();
        await waitFor(() => speech.starts.length > 0);
        await poll(6);
        await wait(500);
        check("2 待機に戻ってから1回", speech.starts, [FAILED]);
    });

    await run("3 返答の読み上げ中に届いた完了は、返答のあとに1回だけ読む", async () => {
        speech.holdEnd = true;
        speakAnswer("（テスト）AI将軍の返答");
        await waitFor(() => speech.starts.length > 0);
        await deliver(activity("営業AI", "販売戦略を作る", "完了", "完了"));
        await wait(300);
        check("3 返答の途中で割り込まない", speech.starts, ["（テスト）AI将軍の返答"]);
        speech.holdEnd = false;
        finishSpeech();
        await waitFor(() => speech.starts.length > 1);
        await poll(6);
        await wait(500);
        check("3 返答のあとに1回", speech.starts, ["（テスト）AI将軍の返答", DONE]);
    });

    await run("4 音声会話の途中に届いた報告は、会話が終わってから1回だけ読む", async () => {
        startVoiceSession();
        await wait(100);
        await deliver(quality("エラー", "WAI-FORMAT"));
        await poll(2);
        await wait(300);
        check("4 会話中は読まない", speech.starts, []);
        endVoiceSession(null);
        await waitFor(() => speech.starts.length > 0, 5000);
        await poll(6);
        await wait(500);
        check("4 会話のあとに1回", speech.starts, [FAILED]);
    });

    await run("5 聞き取り中に届いた成功と失敗は、まとめて1回で読む", async () => {
        startListening();
        await wait(100);
        await deliver(activity("営業AI", "販売戦略を作る", "完了", "完了"), quality("エラー", "WAI-FORMAT"));
        endListeningSilently();
        await waitFor(() => speech.starts.length > 0);
        await poll(6);
        await wait(500);
        check("5 古い順にまとめて1回", speech.starts, [`2件の報告があります。${DONE}。${FAILED}`]);
    });

    await run("6 待機中でも、同じ巡回で届いた複数の報告を取りこぼさない", async () => {
        await deliver(activity("営業AI", "販売戦略を作る", "完了", "完了"), quality("エラー", "WAI-FORMAT"));
        await waitFor(() => speech.starts.length > 0);
        await poll(6);
        await wait(500);
        check("6 2件とも読む", speech.starts, [`2件の報告があります。${DONE}。${FAILED}`]);
    });

    await run("7 報告の読み上げ中に届いた報告は、次の報告として残る", async () => {
        speech.holdEnd = true;
        await deliver(quality("エラー", "WAI-FORMAT"));
        await waitFor(() => speech.starts.length > 0);
        await deliver(activity("営業AI", "販売戦略を作る", "完了", "完了"));
        await wait(300);
        check("7 読み上げ中は割り込まない", speech.starts, [FAILED]);
        speech.holdEnd = false;
        finishSpeech();
        await waitFor(() => speech.starts.length > 1);
        await poll(6);
        await wait(500);
        check("7 次に1回だけ読む", speech.starts, [FAILED, DONE]);
    });

    await run("8a 読み上げを始められなかった報告は失わず、あとで読む", async () => {
        speech.mode = "error";
        await deliver(quality("エラー", "WAI-FORMAT"));
        await wait(500);
        check("8a 始められなかった", speech.starts, []);
        speech.mode = "normal";
        await wait(5200);
        await poll(1);
        await waitFor(() => speech.starts.length > 0);
        await poll(6);
        await wait(500);
        check("8a あとで1回", speech.starts, [FAILED]);
    });

    await run("8b 読み上げの開始も失敗も通知されないときも、失わずにあとで読む", async () => {
        speech.mode = "never";
        await deliver(quality("エラー", "WAI-FORMAT"));
        await wait(5300);
        check("8b 待ちすぎたら待機に戻す", currentVoiceState, "idle");
        check("8b 待ちすぎた1回を失敗に数える", typeof reportStartFailures === "undefined" ? null : reportStartFailures, 1);
        speech.mode = "normal";
        await wait(5200);
        await poll(1);
        await waitFor(() => speech.starts.length > 0);
        await poll(6);
        await wait(500);
        check("8b あとで1回", speech.starts, [FAILED]);
    });

    await run("8c 始められない状態が続いたら3回で再試行をやめ、報告は預かったまま。新しい報告が届いたら一緒に読む", async () => {
        const from = log.length;
        const tries = () => log.slice(from).filter(line => line.startsWith("読み上げ依頼", line.indexOf(" ") + 1)).length;
        speech.mode = "error";
        await deliver(quality("エラー", "WAI-FORMAT"));
        for (let i = 0; i < 4; i++) {
            await wait(5200);
            await poll(1);
        }
        await wait(300);
        check("8c 再試行は3回まで", tries(), 3);
        check("8c 報告は預かったまま", typeof pendingReports === "undefined" ? null : pendingReports, [FAILED]);
        speech.mode = "normal";
        await deliver(activity("営業AI", "販売戦略を作る", "完了", "完了"));
        await waitFor(() => speech.starts.length > 0);
        await poll(6);
        await wait(500);
        check("8c 新しい報告と一緒に1回", speech.starts, [`2件の報告があります。${FAILED}。${DONE}`]);
    });

    await run("8d 諦めたあとに遅れて開始の通知が届いても、二重に読まない", async () => {
        speech.mode = "never";
        await deliver(quality("エラー", "WAI-FORMAT"));
        await wait(5300);
        const late = speech.history.at(-1);
        const cancels = speech.cancels;
        late.onstart?.({});                 // 諦めた読み上げが、遅れて始まった
        await wait(100);
        check("8d 遅れた開始で読み上げ中に戻らない", currentVoiceState, "idle");
        check("8d 遅れて始まった読み上げは止める", speech.cancels > cancels, true);
        late.onend?.({});                   // その終了も遅れて届く
        await wait(100);
        speech.mode = "normal";
        await wait(5200);
        await poll(1);
        await waitFor(() => speech.starts.length > 0);
        await poll(6);
        await wait(500);
        check("8d 再試行で1回だけ読む", speech.starts, [FAILED]);
    });

    await run("8e 開始・終了の通知が二重に届いても、次の報告を消したり止めたりしない", async () => {
        speech.holdEnd = true;
        await deliver(quality("エラー", "WAI-FORMAT"));
        await waitFor(() => speech.starts.length > 0);
        const first = speech.history.at(-1);
        await deliver(activity("営業AI", "販売戦略を作る", "完了", "完了"));
        first.onstart?.({});                // 開始の通知がもう一度届く
        await wait(100);
        finishSpeech();
        await waitFor(() => speech.starts.length > 1);
        first.onend?.({});                  // 終わった読み上げの終了通知が、遅れてもう一度届く
        await wait(100);
        check("8e 次の報告の読み上げ中のまま", [currentVoiceState, document.getElementById("voice-stop-button").hidden],
            ["speaking", false]);
        speech.holdEnd = false;
        finishSpeech();
        await wait(300);
        await poll(6);
        await wait(500);
        check("8e それぞれ1回だけ読む", speech.starts, [FAILED, DONE]);
    });

    await run("9 読み上げ開始後に利用者が止めた報告は、繰り返さない", async () => {
        speech.holdEnd = true;
        await deliver(quality("エラー", "WAI-FORMAT"));
        await waitFor(() => speech.starts.length > 0);
        document.getElementById("voice-stop-button").click();
        speech.holdEnd = false;
        await wait(300);
        await poll(6);
        await wait(500);
        check("9 止めたあと繰り返さない", speech.starts, [FAILED]);
    });

    await run("10 報告の読み上げ中に声で入力すると、読み上げを止めてから聞き取る", async () => {
        speech.holdEnd = true;
        await deliver(quality("エラー", "WAI-FORMAT"));
        await waitFor(() => speech.starts.length > 0);
        const from = log.length;
        document.getElementById("voice-input-button").click();
        await wait(100);
        const listened = log.slice(from).find(line => line.includes("聞き取り開始"));
        check("10 聞き取り開始時に読み上げが止まっている", listened?.includes("読み上げ中: false"), true);
        speech.holdEnd = false;
        document.getElementById("voice-input-button").click();   // 何も話さずに止める（送信しない）
        await wait(300);
        await poll(6);
        await wait(500);
        check("10 止めた報告を繰り返さない", speech.starts, [FAILED]);
        check("10 AI将軍へ送っていない", log.slice(from).some(line => line.includes("AI将軍への送信")), false);
    });

    await run("11 4件以上は件数と省略数を言い、実行ログには全件残る", async () => {
        startListening();
        await wait(100);
        const five = [1, 2, 3, 4, 5].map(n => activity("営業AI", "販売戦略を作る", "完了", `報告${n}`));
        await deliver(...five);
        endListeningSilently();
        await waitFor(() => speech.starts.length > 0);
        await poll(6);
        await wait(500);
        const said = n => `営業AIが「販売戦略を作る」を13秒で終えました。報告${n}`;
        check("11 新しい3件と省略数", speech.starts,
            [`5件の報告があります。新しい3件です。${said(3)}。${said(4)}。${said(5)}。ほか2件は実行ログを確認してください。`]);
        const logText = [...document.querySelectorAll("#hud-log .hud-log-row")].map(row => row.textContent);
        check("11 実行ログに5件とも出ている", [1, 2, 3, 4, 5].map(n => logText.some(text => text.includes(`報告${n}`))),
            [true, true, true, true, true]);
        check("11 5件とも強調", document.querySelectorAll("#hud-log .hud-log-row.is-new").length, 5);
    });

    await run("12 読み込んだ時点ですでに終わっていた作業は読まない", async () => {
        await poll(6);
        await wait(500);
        check("12 読み込み時の完了", speech.starts, []);
    });

    await run("13 拍手起動の待ち受け中は預かり、止めたあとに1回だけ読む", async () => {
        handsFreeStream = { getTracks: () => [] };
        setVoiceState("idle");
        await deliver(quality("エラー", "WAI-FORMAT"));
        await poll(2);
        await wait(300);
        check("13 待ち受け中は読まない", speech.starts, []);
        disableHandsFree();
        await waitFor(() => speech.starts.length > 0);
        await poll(6);
        await wait(500);
        check("13 止めたあとに1回", speech.starts, [FAILED]);
    });

    await run("14 失敗は、担当と処理つきで「エラー」と表示する", async () => {
        await deliver(quality("エラー", "WAI-FORMAT"));
        await wait(300);
        const row = document.querySelector("#hud-log .hud-log-row");
        check("14 実行ログの先頭", [row?.textContent.includes("品質管理AI：公開前の品質を採点する"),
            row?.querySelector(".hud-agent-state")?.textContent], [true, "エラー"]);
        check("14 字幕", document.getElementById("demo-ai-line").textContent, FAILED);
    });

    await reset();
    console.table(results);
    const failed = results.filter(result => result.判定 !== "合格").length;
    console.log(failed === 0 ? "すべて合格" : `${failed}件が不合格`, { 記録: log });
    window.__reportRegression = { results, log };
    return results;
})();
