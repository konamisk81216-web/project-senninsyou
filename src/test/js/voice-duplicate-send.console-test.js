// 音声会話の二重送信の回帰テスト（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 約1分で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、音声認識・読み上げ・AI将軍への送信（/api/ai/command）を、このページの中だけで偽物に差し替える。
// OpenAI は呼ばれず、会話記録や実行記録も DB に保存されない。ほかの読み込み（GET）は普段どおり動く。
// 本物の Chrome の音声認識での確認ではないので、本番での確認（J-4）は別に行うこと。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const sends = [];
    const log = [];
    const t0 = performance.now();
    const note = text => log.push(`${Math.round(performance.now() - t0)}ms ${text}`);
    let script = [];
    let replyDelayMs = 1500;

    // 聞き取り：script の予定を順に使う。{ text, resultAfterMs, endAfterMs, extraEndAfterMs }。予定がなければ無音。
    const proto = (window.SpeechRecognition || window.webkitSpeechRecognition).prototype;
    let serial = 0;
    proto.start = function () {
        const id = ++serial;
        const plan = script.shift();
        this.__stopped = false;
        const fire = (name, event) => { if (typeof this[name] === "function") this[name](event); };
        note(`認識${id} start（${plan ? plan.text : "無音"}）`);
        setTimeout(() => fire("onstart", {}), 30);
        if (!plan) {
            setTimeout(() => { if (!this.__stopped) { fire("onerror", { error: "no-speech" }); fire("onend", {}); } }, 800);
            return;
        }
        setTimeout(() => {
            if (this.__stopped) return;
            fire("onresult", { results: [{ 0: { transcript: plan.text }, length: 1, isFinal: true }] });
        }, plan.resultAfterMs ?? 200);
        const endAt = plan.endAfterMs ?? 350;
        setTimeout(() => { if (!this.__stopped) fire("onend", {}); }, endAt);
        if (plan.extraEndAfterMs) {
            // 同じ聞き取りの終了通知が、遅れてもう一度届く場合。
            setTimeout(() => { note(`認識${id} end（2回目の通知）`); fire("onend", {}); }, endAt + plan.extraEndAfterMs);
        }
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

    const sessionButton = document.getElementById("voice-session-toggle");
    const voiceButton = document.getElementById("voice-input-button");
    const input = document.getElementById("command-input");
    const endSession = () => { if (sessionButton.textContent.includes("終了")) sessionButton.click(); };
    // 前の試験の返答待ち・聞き取りが残らないよう、落ち着くまで待つ。
    const settle = async () => {
        endSession();
        // commandInFlight は修正後の版にだけある。修正前の版でも試せるよう、無ければ「待っていない」とみなす。
        const busy = () => (typeof commandInFlight !== "undefined" && commandInFlight) || voiceListening;
        for (let i = 0; i < 100 && busy(); i++) await wait(100);
        // 修正前の版では返答待ちを判定できないので、返答が戻るまでの時間を余分に待つ。
        if (typeof commandInFlight === "undefined") await wait(replyDelayMs + 500);
        await wait(300);
        script = [];
    };
    const enter = (value, extra = {}) => {
        input.value = value;
        input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, ...extra }));
    };

    const results = [];
    const check = (name, actual, expected) => results.push({ 試験: name, 期待: expected, 結果: actual, 判定: JSON.stringify(actual) === JSON.stringify(expected) ? "合格" : "不合格" });
    const run = async (name, expected, body) => {
        await settle();
        const before = sends.length;
        await body();
        check(name, sends.slice(before), expected);
    };

    await run("S1 手順どおりの2往復", ["今の開発状況を教えて", "その中で最優先は何"], async () => {
        replyDelayMs = 1500;
        script = [{ text: "今の開発状況を教えて" }, { text: "その中で最優先は何" }];
        sessionButton.click();
        await wait(9000);
    });
    await run("S2 終了通知が考え中にもう一度届く", ["今の開発状況を教えて"], async () => {
        replyDelayMs = 6000;
        script = [{ text: "今の開発状況を教えて", extraEndAfterMs: 3000 }];
        sessionButton.click();
        await wait(9000);
    });
    await run("S3 聞き取りが2つ同時に動く", ["次同士押下"], async () => {
        replyDelayMs = 6000;
        script = [{ text: "次どうしようか" }, { text: "次同士押下", resultAfterMs: 5000, endAfterMs: 5200 }];
        sessionButton.click();
        voiceButton.click();
        await wait(14000);
    });
    await run("S4 変換確定の Enter では送らない", [], async () => {
        enter("変換中の文", { isComposing: true });
        await wait(300);
    });
    await run("S5 返答待ちの間の追加送信は受け付けない", ["文字で送る質問"], async () => {
        replyDelayMs = 3000;
        enter("文字で送る質問");
        await wait(200);
        enter("待っている間の2回目");
        await wait(3500);
    });
    await run("S6 返答待ちの間は会話を始めない", ["S6の質問"], async () => {
        replyDelayMs = 3000;
        enter("S6の質問");
        await wait(200);
        sessionButton.click();
        await wait(200);
        check("S6 会話開始ボタンの表示", sessionButton.textContent.trim(), "💬 音声会話を開始");
        await wait(3500);
    });
    await run("通常の Enter は1回送れる", ["返答後の質問"], async () => {
        replyDelayMs = 500;
        enter("返答後の質問");
        await wait(1000);
    });
    await settle();

    console.table(results);
    const failed = results.filter(result => result.判定 !== "合格").length;
    console.log(failed === 0 ? "すべて合格" : `${failed}件が不合格`, { 送信の記録: log });
    window.__voiceRegression = { results, log };
    return results;
})();
