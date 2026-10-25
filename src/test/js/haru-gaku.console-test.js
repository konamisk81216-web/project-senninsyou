// ハル・ガクの話者切り替え（画面・読み上げ）の模擬試験（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 約1分で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、音声認識・読み上げ・声の一覧・司令本部の巡回（/api/command-center）・会話記録（/api/conversations）・
// AI将軍への送信（/api/ai/command）を、このページの中だけで偽物に差し替える。OpenAI は呼ばれず、DB にも書き込まない。
// 読み上げは偽物（ブラウザの順番待ちを真似たもの）なので、実際の声・聞こえ方・実マイクと音声認識の同時使用は別に確かめること。
// 選んだ声の保存（ブラウザの保存領域）は、試験の前の値に戻してから終わる。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const waitFor = async (condition, limitMs = 4000) => {
        for (let waited = 0; waited < limitMs && !condition(); waited += 25) await wait(25);
        return condition();
    };
    const results = [];
    const log = [];
    const t0 = performance.now();
    const now = () => Math.round(performance.now() - t0);
    const note = text => log.push(`${now()}ms ${text}`);
    const $ = id => document.getElementById(id);

    // ===== 声の一覧（偽物） =====
    // 本物の SpeechSynthesisVoice は作れないので、発言に付けた声をそのまま覚えるようにする。
    Object.defineProperty(SpeechSynthesisUtterance.prototype, "voice", {
        configurable: true,
        get() { return this.__voice ?? null; },
        set(value) { this.__voice = value; }
    });
    const VOICE = {
        haruka: { name: "Haruka（端末内）", voiceURI: "local-haruka", lang: "ja-JP", localService: true },
        ichiro: { name: "Ichiro（端末内）", voiceURI: "local-ichiro", lang: "ja-JP", localService: true },
        google: { name: "Google 日本語", voiceURI: "net-google-ja", lang: "ja-JP", localService: false },
        english: { name: "English", voiceURI: "local-en", lang: "en-US", localService: true }
    };
    const synth = window.speechSynthesis;
    let voices = [];
    synth.getVoices = () => voices;
    const setVoices = list => {
        voices = list;
        synth.dispatchEvent(new Event("voiceschanged"));
    };

    // ===== 読み上げ（偽物。ブラウザの順番待ちを真似る） =====
    // speak は順番待ちに並べ、1つずつ「開始→durationMs後に終了」。errorTexts の文は始まらずに失敗し、
    // そのあとも順番待ちは続く（止めるのはアプリの役目）。cancel は今の発言と順番待ちをすべて消し、
    // cancelEvent が "error" なら中断の失敗通知、"end" なら終了通知を送る（ブラウザによって違うため両方試す）。
    const speech = { queue: [], current: null, started: [], ended: [], errorTexts: new Set(), durationMs: 300, cancelEvent: "error", cancels: 0 };
    Object.defineProperty(synth, "speaking", { configurable: true, get: () => Boolean(speech.current?.started) });
    Object.defineProperty(synth, "pending", { configurable: true, get: () => speech.queue.length > 0 || Boolean(speech.current && !speech.current.started) });
    const speechIdle = () => !speech.current && speech.queue.length === 0;
    const voiceName = utterance => utterance.voice ? utterance.voice.name : "標準";
    const playNext = () => {
        if (speech.current || speech.queue.length === 0) return;
        const current = { utterance: speech.queue.shift(), started: false };
        speech.current = current;
        setTimeout(() => {
            if (speech.current !== current) return;
            const utterance = current.utterance;
            if (speech.errorTexts.has(utterance.text)) {
                speech.current = null;
                note(`読み上げ失敗「${utterance.text}」`);
                utterance.onerror?.({ error: "synthesis-failed" });
                setTimeout(playNext, 10);
                return;
            }
            current.started = true;
            speech.started.push({ text: utterance.text, voice: voiceName(utterance), state: currentVoiceState });
            note(`読み上げ開始「${utterance.text}」（${voiceName(utterance)}）`);
            utterance.onstart?.({});
            setTimeout(() => {
                if (speech.current !== current) return;
                speech.current = null;
                speech.ended.push(utterance.text);
                note(`読み上げ終了「${utterance.text}」`);
                utterance.onend?.({});
                setTimeout(playNext, 10);
            }, speech.durationMs);
        }, 40);
    };
    synth.speak = utterance => {
        note(`読み上げ依頼「${utterance.text}」`);
        speech.queue.push(utterance);
        playNext();
    };
    synth.cancel = () => {
        speech.cancels++;
        const dropped = [...(speech.current ? [speech.current] : []), ...speech.queue.map(utterance => ({ utterance, started: false }))];
        speech.current = null;
        speech.queue = [];
        dropped.forEach(item => {
            note(`読み上げ取り消し「${item.utterance.text}」（${speech.cancelEvent}）`);
            setTimeout(() => {
                if (speech.cancelEvent === "end") item.utterance.onend?.({});
                else item.utterance.onerror?.({ error: item.started ? "interrupted" : "canceled" });
            }, 10);
        });
    };

    // ===== 聞き取り（偽物。始まったら、こちらが終わらせるまで発言なしで聞き取り中） =====
    const proto = (window.SpeechRecognition || window.webkitSpeechRecognition).prototype;
    const recognition = { starts: [], held: null };
    proto.start = function () {
        recognition.held = this;
        recognition.starts.push({ at: now(), speechBusy: !speechIdle(), state: currentVoiceState });
        note(`聞き取り開始（読み上げ中: ${!speechIdle()}）`);
        setTimeout(() => this.onstart?.({}), 30);
    };
    proto.stop = function () { setTimeout(() => this.onend?.({}), 20); };
    proto.abort = function () { setTimeout(() => { this.onerror?.({ error: "aborted" }); this.onend?.({}); }, 20); };

    // ===== サーバー（偽物） =====
    // aiReply: AI将軍の返答（オブジェクトならJSONで返す。{ status, text } なら失敗の返答）。
    let aiReply = null;
    let rows = [];
    let savedConversation = [];
    let nextId = 700000;
    const requests = [];
    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init = {}) => {
        const path = new URL(typeof input === "string" ? input : input.url, location.href).pathname;
        const method = (init.method ?? "GET").toUpperCase();
        requests.push({ method, path, body: init.body ?? null });
        if (path === "/api/command-center") {
            return new Response(JSON.stringify({ numbers: null, money: null, priorityTasks: null, agents: null,
                activities: rows.slice(0, 20) }), { headers: { "Content-Type": "application/json" } });
        }
        if (path === "/api/conversations") {
            return new Response(JSON.stringify(savedConversation), { headers: { "Content-Type": "application/json" } });
        }
        if (path === "/api/ai/command") {
            note(`AI将軍への送信「${JSON.parse(init.body).message}」（偽の返答）`);
            await wait(150);
            if (aiReply?.status) return new Response(aiReply.text, { status: aiReply.status });
            return new Response(JSON.stringify(aiReply), { headers: { "Content-Type": "application/json" } });
        }
        if (method !== "GET") {
            note(`想定外の送信 ${method} ${path}（テストでは送らない）`);
            return new Response("テストでは送らない", { status: 503 });
        }
        return realFetch(input, init);
    };
    const alerts = [];
    window.alert = message => { alerts.push(message); note(`alert: ${message}`); };

    // ===== 返答の例 =====
    const HARU = "ハルです。事業としては、小さく試してから広げるのがよいです。";
    const GAKU = "ガクです。技術的には、今の仕組みのままで実現できます。";
    const CLARIFY = "収益を伸ばしたいのか、作業を減らしたいのか、どちらを先に考えますか。";
    const reply = (speaker, fields = {}) => ({
        summary: "結論です。", revenueInsight: "収益の記録はまだありません。", performanceDecision: "データ不足",
        marketOpportunity: "調べることがあります。", nextTask: "", priority: "", assignedAgent: "",
        action: "", actionLabel: "", actionTheme: "", actionAudience: "",
        speaker, speakerReason: "テスト", haruText: "", gakuText: "", clarifyQuestion: "", ...fields
    });
    const BOTH = reply("両方", { haruText: HARU, gakuText: GAKU });
    const BOTH_BODY = `ハル：${HARU}\nガク：${GAKU}`;

    const send = async (text, mode = "discuss") => {
        $("command-input").value = text;
        await sendCommand(mode, { speak: true });
    };
    const groupDone = () => waitFor(() => speechIdle() && currentVoiceState !== "speaking", 5000);
    const messages = () => [...document.querySelectorAll("#ai-response .conversation-message")]
        .map(entry => ({ who: entry.querySelector("strong").textContent, speaker: entry.dataset.speaker,
            text: entry.querySelector("p").textContent, insights: Boolean(entry.querySelector(".conversation-insights")) }));
    const chooseVoice = (speaker, uri) => {
        const select = $(VOICE_SPEAKERS[speaker]);
        select.value = uri;
        select.dispatchEvent(new Event("change"));
    };
    const colorOf = element => element ? getComputedStyle(element).color : null;
    const posts = () => requests.filter(request => request.method !== "GET");
    const commandPosts = () => requests.filter(request => request.path === "/api/ai/command");

    const STORAGE_KEY = "senninsyou.speakerVoices";
    let originalStorage = null;
    try { originalStorage = localStorage.getItem(STORAGE_KEY); localStorage.removeItem(STORAGE_KEY); } catch (error) { /* 保存領域なし */ }
    const realGetItem = Storage.prototype.getItem, realSetItem = Storage.prototype.setItem;

    // 試験ごとに、何も話していない待機中に戻す。
    const reset = async () => {
        voiceSessionActive = false;
        clearTimeout(silenceTimer);
        const held = recognition.held;
        recognition.held = null;
        if (held) { voiceRecognition = null; voiceListening = false; }
        cancelSpeech();
        await wait(80);
        Object.assign(speech, { queue: [], current: null, started: [], ended: [], durationMs: 300, cancelEvent: "error", cancels: 0 });
        speech.errorTexts.clear();
        recognition.starts = [];
        Storage.prototype.getItem = realGetItem;
        Storage.prototype.setItem = realSetItem;
        try { localStorage.removeItem(STORAGE_KEY); } catch (error) { /* 保存領域なし */ }
        loadSpeakerVoices();
        setVoices([VOICE.haruka, VOICE.ichiro, VOICE.google, VOICE.english]);
        await waitFor(() => !commandInFlight);
        pendingReports = [];
        reportRetryAt = 0;
        reportSpeaking = false;
        reportedActivityIds = null;
        reportStartFailures = 0;
        rows = [{ id: String(nextId++), agent: "ライターAI", action: "記事を書く", status: "完了", detail: "読み込み前の完了",
            startedAt: "2026-10-22 10:00:00.0", durationSeconds: "13" }];
        await loadCommandCenter();
        stopSendCountdown();
        setVoiceState("idle", "");
        await wait(60);
        requests.length = 0;
        alerts.length = 0;
        speech.started = [];
        speech.ended = [];
    };
    const check = (name, ok, actual) => {
        results.push({ 試験: name, 判定: ok ? "合格" : "不合格", 実際: JSON.stringify(actual) });
    };
    const run = async (name, body) => {
        await reset();
        note(`--- ${name}`);
        try {
            await body();
        } catch (error) {
            results.push({ 試験: name, 判定: "不合格", 実際: String(error?.stack ?? error) });
        }
    };

    await run("B1", async () => {
        aiReply = BOTH;
        await send("新しい講座をどう始めるか、技術面も含めて教えて");
        await groupDone();
        const shown = messages().slice(-3);
        const labels = [...document.querySelectorAll("#ai-response .conversation-message strong")].slice(-3);
        check("B1 両方：発言ごとに話者名（あなた→ハル→ガク）、内訳は最後の発言だけ",
            JSON.stringify(shown) === JSON.stringify([
                { who: "あなた", speaker: "あなた", text: "新しい講座をどう始めるか、技術面も含めて教えて", insights: false },
                { who: "ハル", speaker: "ハル", text: HARU, insights: false },
                { who: "ガク", speaker: "ガク", text: GAKU, insights: true }]), shown);
        const colors = labels.map(colorOf);
        check("B1 色：ハルは橙、ガクは緑", colors[1] === "rgb(255, 181, 71)" && colors[2] === "rgb(62, 232, 181)", colors);
        check("B1 会話の続きに渡す本文はサーバーの保存と同じ（ハル：…改行ガク：…）",
            conversation.at(-1).role === "AI将軍" && conversation.at(-1).text === BOTH_BODY, conversation.at(-1));
        check("B1 両方・操作なし：承認カードなし", document.querySelectorAll("#ai-response .action-approval").length === 0,
            document.querySelectorAll("#ai-response .action-approval").length);

        speech.started = [];
        aiReply = reply("確認", { clarifyQuestion: CLARIFY, summary: "目的を確かめます。" });
        await send("あれ、どうしたらいい？");
        await groupDone();
        const asked = messages().at(-1);
        check("B1 確認：「AI将軍（確認）」の1発言で、承認カードなし",
            asked.who === "AI将軍（確認）" && asked.text === CLARIFY && messages().at(-2).who === "あなた"
            && document.querySelectorAll("#ai-response .action-approval").length === 0
            && speech.started.map(item => item.text).join("|") === CLARIFY, { asked, spoken: speech.started });
        const history = JSON.parse(commandPosts().at(-1).body).history;
        check("B1 次の相談の続き（history）に、前回の話者つきの本文が入る", history.includes(`AI将軍: ${BOTH_BODY}`), history);

        speech.started = [];
        aiReply = reply("ハル", { haruText: HARU });
        await send("売り方だけ相談したい");
        await groupDone();
        aiReply = reply("ガク", { gakuText: GAKU });
        await send("ガクに聞きたい。実装できる？");
        await groupDone();
        const singles = messages().slice(-3).map(item => item.who);
        check("B1 ハルだけ・ガクだけ：その1人の発言だけ",
            JSON.stringify(singles) === JSON.stringify(["ハル", "あなた", "ガク"]) && speech.started.map(item => item.text).join("|") === `${HARU}|${GAKU}`,
            { singles, spoken: speech.started.map(item => item.text) });
    });

    await run("B2", async () => {
        chooseVoice("ハル", VOICE.haruka.voiceURI);
        chooseVoice("ガク", VOICE.ichiro.voiceURI);
        voiceSessionActive = true;
        aiReply = BOTH;
        await send("両方の意見を聞きたい");
        await waitFor(() => recognition.starts.length > 0, 5000);
        await wait(100);
        check("B2 ハル→ガクの順に、それぞれの声で読む",
            JSON.stringify(speech.started.map(item => [item.text, item.voice])) === JSON.stringify([[HARU, VOICE.haruka.name], [GAKU, VOICE.ichiro.name]]),
            speech.started);
        check("B2 発言の間も「読み上げ中」のまま（ガクの開始時点）", speech.started[1]?.state === "speaking", speech.started.map(item => item.state));
        check("B2 聞き取りは最後の発言（ガク）が終わってからの1回だけ",
            recognition.starts.length === 1 && recognition.starts[0].speechBusy === false && speech.ended.join("|") === `${HARU}|${GAKU}`,
            { starts: recognition.starts, ended: speech.ended });
    });

    await run("B3", async () => {
        aiReply = BOTH;
        await send("両方の意見を聞きたい");
        await waitFor(() => speech.started.length === 1);
        rows = [{ id: String(nextId++), agent: "営業AI", action: "販売戦略を作る", status: "完了", detail: "完了",
            startedAt: "2026-10-22 10:01:00.0", durationSeconds: "13" }, ...rows];
        await loadCommandCenter();
        await groupDone();
        await waitFor(() => speech.started.length >= 3, 3000);
        await wait(800);
        await loadCommandCenter();
        await wait(500);
        const spoken = speech.started.map(item => item.text);
        const report = "営業AIが「販売戦略を作る」を13秒で終えました。完了";
        check("B3 報告はハルとガクの間に挟まらず、グループの後に1回だけ（報告は標準の声）",
            JSON.stringify(spoken) === JSON.stringify([HARU, GAKU, report]) && speech.started[2].voice === "標準", speech.started);
    });

    await run("B4", async () => {
        aiReply = BOTH;
        speech.durationMs = 600;
        await send("両方の意見を聞きたい");
        await waitFor(() => speech.started.length === 1);
        rows = [{ id: String(nextId++), agent: "営業AI", action: "販売戦略を作る", status: "完了", detail: "完了",
            startedAt: "2026-10-22 10:02:00.0", durationSeconds: "13" }, ...rows];
        await loadCommandCenter();
        await wait(100);
        $("voice-stop-button").click();
        await wait(1500);
        const spoken = speech.started.map(item => item.text);
        const report = "営業AIが「販売戦略を作る」を13秒で終えました。完了";
        check("B4 停止でガクは読まれず、待機に戻り、預かった報告は失わない（停止後に1回）",
            JSON.stringify(spoken) === JSON.stringify([HARU, report]) && currentVoiceState === "idle", { spoken, state: currentVoiceState });

        // 止めた発言・順番待ちの発言の通知が「終わった」で届くブラウザでも、会話中に聞き取りを再開しない。
        await reset();
        aiReply = BOTH;
        speech.durationMs = 600;
        speech.cancelEvent = "end";
        voiceSessionActive = true;
        await send("両方の意見を聞きたい");
        await waitFor(() => speech.started.length === 1);
        $("voice-stop-button").click();
        await wait(1200);
        check("B4b 止めた通知が「終わった」で届いても、ガクは読まれず、聞き取りも再開しない",
            speech.started.length === 1 && recognition.starts.length === 0 && currentVoiceState === "idle",
            { spoken: speech.started.map(item => item.text), starts: recognition.starts, state: currentVoiceState });
    });

    await run("B5", async () => {
        aiReply = BOTH;
        speech.durationMs = 600;
        await send("両方の意見を聞きたい");
        await waitFor(() => speech.started.length === 1);
        $("voice-input-button").click();
        await wait(1200);
        check("B5 ハルの途中で話しかけると、ガクは読まれず、聞き取りが1回だけ始まる",
            speech.started.length === 1 && recognition.starts.length === 1 && currentVoiceState === "listening",
            { spoken: speech.started.map(item => item.text), starts: recognition.starts, state: currentVoiceState });
        check("B5 送信は相談の1回だけ", commandPosts().length === 1, commandPosts().length);
    });

    await run("B6", async () => {
        chooseVoice("ハル", VOICE.haruka.voiceURI);
        chooseVoice("ガク", VOICE.ichiro.voiceURI);
        aiReply = BOTH;
        await send("両方の意見を聞きたい");
        await groupDone();
        speech.started = [];
        $("voice-read-button").click();
        await groupDone();
        check("B6 「もう一度聞く」は、グループ全体を同じ順番・同じ声で読む（送信はしない）",
            JSON.stringify(speech.started.map(item => [item.text, item.voice])) === JSON.stringify([[HARU, VOICE.haruka.name], [GAKU, VOICE.ichiro.name]])
            && commandPosts().length === 1, speech.started);
    });

    await run("B7", async () => {
        chooseVoice("ハル", VOICE.haruka.voiceURI);
        // 再読み込みと同じ状態：画面の会話は空、直前のグループの情報もない。保存済みの会話を読み込み直す。
        savedConversation = [{ role: "利用者", message: "両方の意見を聞きたい" }, { role: "AI将軍", message: BOTH_BODY }];
        conversation.length = 0;
        lastSpeechGroup = null;
        $("ai-response").replaceChildren();
        await restoreConversation();
        const shown = messages();
        check("B7 再読み込み後は「AI将軍」名義で本文をそのまま表示（話者カードに分けない）",
            JSON.stringify(shown.map(item => [item.who, item.text])) === JSON.stringify([["あなた", "両方の意見を聞きたい"], ["AI将軍", BOTH_BODY]]), shown);
        $("voice-read-button").click();
        await groupDone();
        check("B7 「もう一度聞く」は保存した本文を1回、1つの文として読む（話者の声は使わない）",
            JSON.stringify(speech.started.map(item => [item.text, item.voice])) === JSON.stringify([[BOTH_BODY, "標準"]]), speech.started);
    });

    await run("B8", async () => {
        aiReply = BOTH;
        voiceSessionActive = true;
        speech.errorTexts.add(GAKU);
        await send("両方の意見を聞きたい");
        await wait(1500);
        check("B8 ガクの読み上げが失敗：グループを終え、文は残り、聞き取りは再開しない",
            currentVoiceState === "idle" && recognition.starts.length === 0 && messages().at(-1).text === GAKU
            && $("voice-status").textContent.includes("読み上げに失敗しました"),
            { state: currentVoiceState, starts: recognition.starts, last: messages().at(-1), status: $("voice-status").textContent });

        await reset();
        aiReply = BOTH;
        voiceSessionActive = true;
        speech.errorTexts.add(HARU);
        await send("両方の意見を聞きたい");
        await wait(1500);
        check("B8b ハルで失敗したら、残りのガクも読まない（ブラウザが続けようとしても止める）",
            speech.started.length === 0 && recognition.starts.length === 0 && currentVoiceState === "idle",
            { spoken: speech.started, starts: recognition.starts, state: currentVoiceState });
    });

    await run("B9", async () => {
        const before = messages().length;
        aiReply = { status: 500, text: "AI将軍の返答形式が正しくありません。" };
        await send("両方の意見を聞きたい");
        await wait(500);
        check("B9 失敗の返答：既存のエラー表示、読み上げなし、会話・承認カードは増えない、送信は1回",
            currentVoiceState === "error" && alerts.length === 1 && speech.started.length === 0 && messages().length === before
            && document.querySelectorAll("#ai-response .action-approval").length === 0 && commandPosts().length === 1,
            { state: currentVoiceState, alerts, spoken: speech.started.length, messages: messages().length - before });
    });

    await run("B10", async () => {
        aiReply = reply("両方", { haruText: HARU, gakuText: GAKU, action: "runQuality", actionLabel: "品質管理AIに直近の記事を採点させます。" });
        await send("記事の質を確かめたい");
        await groupDone();
        await wait(300);
        const cards = [...document.querySelectorAll("#ai-response .action-approval")];
        check("B10 両方＋操作の提案：承認カードは1つで、見出しは「AI将軍の操作の提案（承認すると実行）」",
            cards.length === 1 && cards[0].querySelector(".action-approval-heading")?.textContent === "AI将軍の操作の提案（承認すると実行）"
            && cards[0].querySelector("strong")?.textContent === "品質管理AIに直近の記事を採点させます。",
            cards.map(card => card.textContent));
        check("B10 押すまで実行しない（相談の送信以外の送信なし）",
            posts().length === 1 && posts()[0].path === "/api/ai/command", posts().map(post => post.path));
        cards.forEach(card => card.querySelector(".secondary-button")?.click());
    });

    await run("B11", async () => {
        showPage("talk");
        await wait(300);
        aiReply = BOTH;
        speech.durationMs = 700;
        const seen = [];
        const sample = () => ({
            demo: [$("demo-ai-line").dataset.speaker, $("demo-ai-line").textContent],
            talk: [$("talk-sub-ai-who").textContent, $("talk-sub-ai").textContent, colorOf($("talk-sub-ai-who"))],
            hue: window.__talk?.view.hue?.join(","), state: currentVoiceState
        });
        await send("両方の意見を聞きたい");
        await waitFor(() => speech.started.length === 1);
        await wait(300);
        seen.push(sample());
        await waitFor(() => speech.started.length === 2);
        await wait(300);
        seen.push(sample());
        await groupDone();
        check("B11 ハルの発言中：字幕・会話画面の話者名・色・発光体の色がハル",
            JSON.stringify(seen[0]) === JSON.stringify({ demo: ["ハル", HARU], talk: ["ハル", HARU, "rgb(255, 181, 71)"], hue: "255,181,71", state: "speaking" }), seen[0]);
        check("B11 ガクの発言中：字幕・会話画面の話者名・色・発光体の色がガク",
            JSON.stringify(seen[1]) === JSON.stringify({ demo: ["ガク", GAKU], talk: ["ガク", GAKU, "rgb(62, 232, 181)"], hue: "62,232,181", state: "speaking" }), seen[1]);
        setDemoSubtitle(null, "AI社員の報告です。");
        await wait(100);
        check("B11 話者のない文（報告など）は「AI将軍」に戻る", $("talk-sub-ai-who").textContent === "AI将軍" && !$("demo-ai-line").dataset.speaker,
            $("talk-sub-ai-who").textContent);
        showPage("command");
    });

    await run("B12", async () => {
        // 話者の項目がない返答（タスク案の整理・導入前の形）は、今までどおりAI将軍の1発言。
        aiReply = { summary: "タスク案です。", nextTask: "記事を1本書く", priority: "中", assignedAgent: "ライターAI",
            revenueInsight: "", performanceDecision: "", marketOpportunity: "", action: "", actionLabel: "", actionTheme: "", actionAudience: "" };
        await sendCommand("propose", { speak: true });
        await groupDone();
        check("B12 タスク案の整理はAI将軍の1発言（話者を使わない）",
            messages().at(-1).who === "AI将軍" && messages().at(-1).text === "タスク案です。" && conversation.at(-1).text === "タスク案です。"
            && speech.started.map(item => item.text).join("|") === "タスク案です。", { last: messages().at(-1), spoken: speech.started });
        aiReply = { summary: "前の形の返答です。", revenueInsight: "", performanceDecision: "", marketOpportunity: "", nextTask: "", priority: "",
            assignedAgent: "", action: "", actionLabel: "", actionTheme: "", actionAudience: "" };
        await send("前の形で答えて");
        await groupDone();
        check("B12 話者の項目がない相談の返答も、今までどおりAI将軍の1発言",
            messages().at(-1).who === "AI将軍" && messages().at(-1).text === "前の形の返答です。", messages().at(-1));
    });

    await run("B13", async () => {
        const options = [...$(VOICE_SPEAKERS["ハル"]).options].map(option => option.textContent);
        check("B13 声の一覧：標準＋日本語の声。ネット経由の声には「（ネット経由）」",
            JSON.stringify(options) === JSON.stringify(["ブラウザの標準の声", VOICE.haruka.name, VOICE.ichiro.name, `${VOICE.google.name}（ネット経由）`]), options);
        requests.length = 0;
        chooseVoice("ハル", VOICE.haruka.voiceURI);
        chooseVoice("ガク", VOICE.google.voiceURI);
        const stored = JSON.parse(localStorage.getItem(STORAGE_KEY));
        check("B13 選んだ声はブラウザの保存領域に残り、保存でサーバーへの送信は起きない",
            stored?.["ハル"]?.uri === VOICE.haruka.voiceURI && stored?.["ガク"]?.uri === VOICE.google.voiceURI && requests.length === 0,
            { stored, requests });
        // 再読み込みと同じ：覚えている声を消してから、保存領域から読み直す。
        speakerVoices = {};
        loadSpeakerVoices();
        renderSpeakerVoiceOptions();
        aiReply = BOTH;
        await send("両方の意見を聞きたい");
        await groupDone();
        check("B13 読み直したあとも選んだ声で読む（自分で選んだネット経由の声は使う）",
            $(VOICE_SPEAKERS["ハル"]).value === VOICE.haruka.voiceURI
            && JSON.stringify(speech.started.map(item => item.voice)) === JSON.stringify([VOICE.haruka.name, VOICE.google.name]), speech.started);
        chooseVoice("ガク", "");
        check("B13 「ブラウザの標準の声」に戻すと保存から消える", !("ガク" in JSON.parse(localStorage.getItem(STORAGE_KEY))),
            localStorage.getItem(STORAGE_KEY));
    });

    await run("B14", async () => {
        localStorage.setItem(STORAGE_KEY, JSON.stringify({ "ハル": { uri: "gone-voice", name: "消えた声" } }));
        loadSpeakerVoices();
        renderSpeakerVoiceOptions();
        check("B14 声の選択欄に、選んだ声が見つからないことを出す",
            $("speaker-voice-note").textContent.includes("選んだハルの声（消えた声）が見つかりません"), $("speaker-voice-note").textContent);
        aiReply = BOTH;
        await send("両方の意見を聞きたい");
        await waitFor(() => speech.started.length === 1);
        const status = $("voice-status").textContent;
        await groupDone();
        check("B14 選んだ声が使えない：通知し、ネット経由ではなく端末内の日本語の声で読む（ガクは選んでいないので標準）",
            status.includes("選んだハルの声（消えた声）が見つかりません") && status.includes(VOICE.haruka.name)
            && JSON.stringify(speech.started.map(item => item.voice)) === JSON.stringify([VOICE.haruka.name, "標準"]), { status, spoken: speech.started });

        await reset();
        localStorage.setItem(STORAGE_KEY, JSON.stringify({ "ハル": { uri: "gone-voice", name: "消えた声" } }));
        loadSpeakerVoices();
        setVoices([VOICE.google, VOICE.english]);
        voiceSessionActive = true;
        aiReply = BOTH;
        await send("両方の意見を聞きたい");
        await wait(1000);
        const silentStatus = $("voice-status").textContent;
        check("B14b 端末内の日本語の声もない：読み上げず（ネット経由にも切り替えず）、文字だけ表示して選び直しを案内",
            speech.started.length === 0 && messages().at(-1).text === GAKU && currentVoiceState === "idle" && recognition.starts.length === 0
            && silentStatus.includes("読み上げずに文字だけ表示") && silentStatus.includes("声を選び直してください"),
            { spoken: speech.started, status: silentStatus, state: currentVoiceState, starts: recognition.starts });

        await reset();
        aiReply = BOTH;
        await send("両方の意見を聞きたい");
        await groupDone();
        check("B14c 声を選んでいなければ、ブラウザの標準の声のまま（声の一覧から勝手に選ばない）",
            JSON.stringify(speech.started.map(item => item.voice)) === JSON.stringify(["標準", "標準"]), speech.started);
    });

    await run("B15", async () => {
        Storage.prototype.setItem = () => { throw new DOMException("blocked", "SecurityError"); };
        Storage.prototype.getItem = () => { throw new DOMException("blocked", "SecurityError"); };
        chooseVoice("ガク", VOICE.ichiro.voiceURI);
        check("B15 保存領域が使えない：選んだ声はこのページを開いている間だけ有効と表示",
            $("speaker-voice-note").textContent.includes("このページを開いている間だけ"), $("speaker-voice-note").textContent);
        aiReply = BOTH;
        await send("両方の意見を聞きたい");
        await groupDone();
        check("B15 読み上げと会話は止まらず、選んだ声で読む",
            JSON.stringify(speech.started.map(item => [item.text, item.voice])) === JSON.stringify([[HARU, "標準"], [GAKU, VOICE.ichiro.name]])
            && commandPosts().length === 1, speech.started);
        loadSpeakerVoices();
        renderSpeakerVoiceOptions();
        check("B15 読み込み時に保存領域が使えないときも、その旨を表示", $("speaker-voice-note").textContent.includes("このページを開いている間だけ"),
            $("speaker-voice-note").textContent);
    });

    await run("B16", async () => {
        chooseVoice("ハル", VOICE.haruka.voiceURI);
        $("speaker-voice-haru-test").click();
        await groupDone();
        check("B16 試聴：待機中は、選んだ声で短い文を1回読む（送信しない）",
            JSON.stringify(speech.started.map(item => [item.text, item.voice])) === JSON.stringify([["ハルです。この声で読み上げます。", VOICE.haruka.name]])
            && posts().length === 0, speech.started);
        speech.started = [];
        voiceSessionActive = true;
        $("speaker-voice-gaku-test").click();
        await wait(500);
        check("B16 会話中は試聴しない（会話の読み上げ・聞き取りと重ねない）",
            speech.started.length === 0 && $("speaker-voice-note").textContent.includes("試聴できません"), $("speaker-voice-note").textContent);
    });

    await reset();
    try {
        if (originalStorage === null) localStorage.removeItem(STORAGE_KEY);
        else localStorage.setItem(STORAGE_KEY, originalStorage);
    } catch (error) { /* 保存領域なし */ }
    loadSpeakerVoices();
    console.table(results);
    const failed = results.filter(result => result.判定 !== "合格").length;
    console.log(failed === 0 ? "すべて合格" : `${failed}件が不合格`, { 記録: log });
    window.__haruGakuTest = { results, log };
    return results;
})();
