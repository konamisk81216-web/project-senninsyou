// 「会話を終了」で、まだ送っていない音声入力を送らないことの回帰テスト（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 約1分で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、音声認識・読み上げ・AI将軍への送信（/api/ai/command）を、このページの中だけで偽物に差し替える。
// OpenAI は呼ばれず、会話記録や実行記録も DB に保存されない。ほかの読み込み（GET）は普段どおり動く。
// 偽の音声認識は、止めたあとにも結果や終了の通知を遅れて届けられる（本物のブラウザで起こりうる遅れを真似る）。
// 本物の Chrome の音声認識・実マイクでの確認ではない。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const sends = [];
    const log = [];
    const t0 = performance.now();
    const note = text => log.push(`${Math.round(performance.now() - t0)}ms ${text}`);
    let script = [];
    let replyDelayMs = 1500;
    let starts = 0;

    // 聞き取り：script の予定を順に使う。{ text, resultAfterMs, endAfterMs, late }。予定がなければ無音。
    // late が true の予定は、stop・abort されたあとも onstart・onresult・onend を予定どおり届ける。
    const proto = (window.SpeechRecognition || window.webkitSpeechRecognition).prototype;
    let serial = 0;
    proto.start = function () {
        const id = ++serial;
        starts += 1;
        const plan = script.shift();
        this.__stopped = false;
        const fire = (name, event) => { if (typeof this[name] === "function") this[name](event); };
        const due = () => !this.__stopped || plan?.late;
        note(`認識${id} start（${plan ? plan.text : "無音"}${plan?.late ? "・止めても遅れて届く" : ""}）`);
        setTimeout(() => { if (due()) fire("onstart", {}); }, plan?.startAfterMs ?? 30);
        if (!plan) {
            setTimeout(() => { if (!this.__stopped) { fire("onerror", { error: "no-speech" }); fire("onend", {}); } }, 800);
            return;
        }
        setTimeout(() => {
            if (!due()) return;
            if (this.__stopped) note(`認識${id} 止めたあとに結果が届く`);
            fire("onresult", { results: [{ 0: { transcript: plan.text }, length: 1, isFinal: true }] });
        }, plan.resultAfterMs ?? 200);
        setTimeout(() => {
            if (!due()) return;
            if (this.__stopped) note(`認識${id} 止めたあとに終了が届く`);
            fire("onend", {});
        }, plan.endAfterMs ?? 350);
    };
    proto.stop = function () { this.__stopped = true; setTimeout(() => this.onend && this.onend({}), 20); };
    proto.abort = function () { this.__stopped = true; setTimeout(() => { this.onerror && this.onerror({ error: "aborted" }); this.onend && this.onend({}); }, 20); };

    // 読み上げ：少し待って終わったことにする。
    window.speechSynthesis.speak = utterance => {
        setTimeout(() => utterance.onstart && utterance.onstart({}), 10);
        setTimeout(() => utterance.onend && utterance.onend({}), 400);
    };
    window.speechSynthesis.cancel = () => {};

    // AI将軍への送信：数えるだけで、サーバーには送らない。
    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
        const path = new URL(typeof input === "string" ? input : input.url, location.href).pathname;
        if (path === "/api/ai/command") {
            const message = JSON.parse(init.body).message;
            sends.push(message);
            note(`送信「${message}」`);
            await wait(replyDelayMs);
            return new Response(JSON.stringify({ summary: "テスト用の返答", nextTask: "", priority: "", assignedAgent: "" }),
                { headers: { "Content-Type": "application/json" } });
        }
        return realFetch(input, init);
    };
    window.alert = message => note(`alert: ${message}`);

    const $ = id => document.getElementById(id);
    const sessionButton = $("voice-session-toggle");
    const voiceButton = $("voice-input-button");
    const cancelButton = $("voice-cancel-send");
    const input = $("command-input");
    const status = () => $("voice-status").textContent;
    const inSession = () => sessionButton.textContent.includes("終了");
    const endSession = () => { if (inSession()) sessionButton.click(); };
    const waitFor = async (condition, limitMs = 6000) => {
        for (let waited = 0; waited < limitMs && !condition(); waited += 25) await wait(25);
        return condition();
    };
    // 前の試験の返答待ち・聞き取りが残らないよう、落ち着くまで待つ。
    const settle = async () => {
        endSession();
        for (let i = 0; i < 100 && (commandInFlight || voiceListening); i++) await wait(100);
        await wait(3000);
        script = [];
    };

    const results = [];
    const run = async (name, body) => {
        await settle();
        const before = sends.length;
        const startsBefore = starts;
        note(`--- ${name}`);
        const verdict = await body({ sent: () => sends.slice(before), started: () => starts - startsBefore });
        results.push({ 試験: name, 判定: verdict.ok ? "合格" : "不合格", 詳細: JSON.stringify(verdict.detail) });
    };

    await run("E1 送信前の待ち時間（2秒）に会話を終了すると送らない", async ({ sent }) => {
        script = [{ text: "送ってはいけない発言" }];
        sessionButton.click();
        const confirming = await waitFor(() => status().includes("秒後に将軍へ送ります"));
        sessionButton.click();
        const afterEnd = { status: status(), input: input.value, cancelHidden: cancelButton.hidden };
        await wait(4000);
        const ok = confirming && sent().length === 0 && afterEnd.status.includes("聞き取った発言は送信していません")
            && !afterEnd.status.includes("取り消していません") && afterEnd.input === "" && afterEnd.cancelHidden && !inSession();
        return { ok, detail: { confirming, sent: sent(), ...afterEnd } };
    });

    await run("E2 聞き取り中に終了し、そのあと結果と終了の通知が遅れて届いても送らない", async ({ sent }) => {
        script = [{ text: "遅れて届く発言", resultAfterMs: 1500, endAfterMs: 1700, late: true }];
        sessionButton.click();
        const listening = await waitFor(() => voiceListening);
        sessionButton.click();
        await wait(5000);
        const ok = listening && sent().length === 0 && !status().includes("秒後に将軍へ送ります") && cancelButton.hidden && !voiceListening;
        return { ok, detail: { listening, sent: sent(), status: status(), input: input.value } };
    });

    await run("E3 聞き取りの開始通知より前に終了し、そのあと開始・結果・終了が遅れて届いても送らない", async ({ sent }) => {
        script = [{ text: "開始前に終了した発言", startAfterMs: 400, resultAfterMs: 900, endAfterMs: 1100, late: true }];
        sessionButton.click();
        sessionButton.click();
        await wait(5000);
        const ok = sent().length === 0 && !status().includes("秒後に将軍へ送ります") && cancelButton.hidden && !voiceListening;
        return { ok, detail: { sent: sent(), status: status(), listening: voiceListening } };
    });

    await run("E4 送信済み（返答待ち）で終了すると、取り消したとは表示せず、返答後に聞き取りを再開しない", async ({ sent, started }) => {
        replyDelayMs = 3000;
        script = [{ text: "すでに送った発言" }];
        sessionButton.click();
        const sentNow = await waitFor(() => sent().length === 1 && commandInFlight);
        sessionButton.click();
        const afterEnd = status();
        await waitFor(() => !commandInFlight, 8000);
        await wait(2500);
        replyDelayMs = 1500;
        const ok = sentNow && sent().length === 1 && afterEnd.includes("送信済みの発言は取り消していません")
            && !afterEnd.includes("聞き取った発言は送信していません") && started() === 1 && !inSession();
        return { ok, detail: { sent: sent(), afterEnd, recognitionStarts: started() } };
    });

    await run("E5 通常の連続会話（2往復）は今までどおり送る", async ({ sent }) => {
        script = [{ text: "今の開発状況を教えて" }, { text: "その中で最優先は何" }];
        sessionButton.click();
        await waitFor(() => sent().length === 2 && !commandInFlight, 15000);
        await wait(500);
        endSession();
        const ok = JSON.stringify(sent()) === JSON.stringify(["今の開発状況を教えて", "その中で最優先は何"]);
        return { ok, detail: { sent: sent() } };
    });

    await run("E6 既存の「送信を取り消す」（声で入力1回）は今までどおり送らない", async ({ sent }) => {
        script = [{ text: "取り消す発言" }];
        voiceButton.click();
        const confirming = await waitFor(() => status().includes("秒後に将軍へ送ります") && !cancelButton.hidden);
        cancelButton.click();
        const afterCancel = status();
        await wait(4000);
        const ok = confirming && sent().length === 0 && afterCancel.includes("送信を取り消しました") && input.value === "";
        return { ok, detail: { confirming, sent: sent(), afterCancel } };
    });

    await run("E7 既存の「送信を取り消す」（音声会話中）も送らない", async ({ sent }) => {
        script = [{ text: "会話中に取り消す発言" }];
        sessionButton.click();
        const confirming = await waitFor(() => status().includes("秒後に将軍へ送ります") && !cancelButton.hidden);
        cancelButton.click();
        await wait(4000);
        endSession();
        const ok = confirming && sent().length === 0;
        return { ok, detail: { confirming, sent: sent(), status: status() } };
    });

    await run("E8 待ち時間なし：声で入力1回は2秒後に1回だけ送る", async ({ sent }) => {
        script = [{ text: "1回だけ送る発言" }];
        voiceButton.click();
        await waitFor(() => sent().length === 1 && !commandInFlight, 8000);
        await wait(1500);
        const ok = JSON.stringify(sent()) === JSON.stringify(["1回だけ送る発言"]);
        return { ok, detail: { sent: sent() } };
    });

    await settle();
    console.table(results);
    console.log(log.join("\n"));
    return results;
})();
