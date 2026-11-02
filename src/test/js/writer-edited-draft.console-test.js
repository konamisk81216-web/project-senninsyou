// 「直した下書きを保存」の模擬試験（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 数秒で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、下書きの保存・直近の下書き・品質管理AI（/api/ai/writer/...）への通信を、このページの中だけで偽物に差し替える。
// OpenAI は呼ばれず、DB にも書き込まない。画面の下書き欄とブラウザ内の下書きの保存は、試験の前の状態に戻してから終わる。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const $ = id => document.getElementById(id);
    const log = [];
    const requests = [];

    // 下書き欄とブラウザ内の保存を、試験の前の状態に戻せるよう控えておく。
    const fieldIds = ["writer-theme", "writer-audience", "writer-price", "writer-source-notes", "writer-output-title",
        "writer-output-free", "writer-output-paid", "writer-output-sales", "writer-output-sns", "writer-output-review"];
    const beforeFields = Object.fromEntries(fieldIds.map(id => [id, $(id).value]));
    let beforeStorage = null;
    try { beforeStorage = localStorage.getItem(WRITER_DRAFT_KEY); } catch (error) { /* 保存領域が使えない環境 */ }

    // サーバー（偽物）：最初の直近の下書きは、AIが書いた元の版。保存すると、直した版が直近になる。
    const original = {
        id: "10", status: "完了", theme: "元のテーマ", audience: "元の読者", price: "500円",
        title: "AIの元のタイトル", freeSection: "AIの元の無料部分", paidSection: "AIの元の有料部分", salesDescription: "AIの元の説明"
    };
    let latest = original;
    let saveReply = () => ({ status: 200, body: { id: "11", status: "完了" } });
    let saveDelayMs = 0;
    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init = {}) => {
        const path = new URL(typeof input === "string" ? input : input.url, location.href).pathname;
        if (!path.startsWith("/api/ai/")) return realFetch(input, init);
        const body = init.body ? JSON.parse(init.body) : null;
        requests.push({ path, method: init.method ?? "GET", body });
        log.push(`${init.method ?? "GET"} ${path}`);
        const json = (status, value) => new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
        if (path === "/api/ai/writer/jobs/edited") {
            await wait(saveDelayMs);
            const reply = saveReply();
            if (reply.status === 200 && !reply.body.errorCode) latest = { ...body, id: reply.body.id, status: "完了" };
            return json(reply.status, reply.body);
        }
        if (path === "/api/ai/writer/jobs/latest") return json(200, latest);
        if (path === "/api/ai/writer/quality") return json(200, { score: 70, verdict: "改善後に公開", strengths: "", improvements: "", questions: "" });
        return json(500, { error: "この試験では使わない通信" });
    };

    const button = $("writer-save-edited");
    const status = () => $("writer-status").textContent;
    const fill = values => { for (const [id, value] of Object.entries(values)) $(id).value = value; };
    const complete = {
        "writer-theme": "AIで事務作業を減らす", "writer-audience": "個人事業主", "writer-price": "500円",
        "writer-source-notes": "実体験の手順", "writer-output-title": "本人が直したタイトル",
        "writer-output-free": "本人が直した無料部分", "writer-output-paid": "本人が直した有料部分",
        "writer-output-sales": "本人が直した説明", "writer-output-sns": "投稿案", "writer-output-review": "確認済み"
    };
    const since = count => requests.slice(count);
    const results = [];
    const check = (name, ok, detail) => results.push({ 試験: name, 判定: ok ? "合格" : "不合格", 詳細: JSON.stringify(detail) });

    try {
        // W1 必須の項目が空なら送らない
        fill({ ...complete, "writer-output-title": "", "writer-output-sales": "  " });
        let count = requests.length;
        button.click();
        await wait(100);
        check("W1 必須の項目が空なら送らず、足りない項目を案内する",
            since(count).length === 0 && status().includes("タイトル") && status().includes("販売ページの説明"),
            { requests: since(count), status: status() });

        // W2 保存する前は、会話から承認した品質管理AIは、AIの元の版を採点する（今までの動き）
        count = requests.length;
        await runQualityAction();
        const qualityBefore = since(count).find(r => r.path === "/api/ai/writer/quality");
        check("W2 保存する前は、品質管理AIの対象はAIの元の版のまま",
            qualityBefore?.body.title === "AIの元のタイトル", { quality: qualityBefore?.body.title });

        // W3 保存すると、直した内容を1回だけ送り、番号と「元の下書きは残っている」を表示する。AIの通信はしない
        fill(complete);
        count = requests.length;
        button.click();
        await wait(200);
        const sent = since(count);
        const saved = sent[0]?.body ?? {};
        check("W3 直した内容を1回だけ送り、番号と元の下書きが残ることを表示する（AIは呼ばない）",
            sent.length === 1 && sent[0].path === "/api/ai/writer/jobs/edited" && sent[0].method === "POST"
                && saved.title === "本人が直したタイトル" && saved.paidSection === "本人が直した有料部分"
                && saved.sourceNotes === "実体験の手順" && "interviewNotes" in saved
                && status().includes("下書き番号 11") && status().includes("元のAIの下書きはそのまま残っています"),
            { sent, status: status() });

        // W4 保存したあとは、会話から承認した品質管理AIが直した版を採点する
        count = requests.length;
        await runQualityAction();
        const qualityAfter = since(count).find(r => r.path === "/api/ai/writer/quality");
        check("W4 保存後は、品質管理AIの対象が直した版になる",
            qualityAfter?.body.title === "本人が直したタイトル" && qualityAfter?.body.paidSection === "本人が直した有料部分",
            { quality: qualityAfter?.body });

        // W5 保存に失敗したら、保存できなかったと案内し、ボタンを戻す
        saveReply = () => ({ status: 200, body: { errorCode: "WAI-DB" } });
        button.click();
        await wait(200);
        const dbFailure = status();
        saveReply = () => ({ status: 500, body: { error: "サーバーエラー" } });
        button.click();
        await wait(200);
        check("W5 DBに保存できない・サーバーが失敗したときは、保存できなかったと案内してボタンを戻す",
            dbFailure.includes("保存できませんでした") && status().includes("保存できませんでした")
                && !button.disabled && button.textContent === "直した下書きを保存" && latest.title === "本人が直したタイトル",
            { dbFailure, serverFailure: status(), disabled: button.disabled });

        // W6 保存中はボタンを押せない（同じ版を二重に登録しない）
        saveReply = () => ({ status: 200, body: { id: "12", status: "完了" } });
        saveDelayMs = 500;
        count = requests.length;
        button.click();
        await wait(50);
        const disabledWhileSaving = button.disabled;
        button.click();
        await wait(800);
        check("W6 保存中はボタンを押せず、1回だけ送る",
            disabledWhileSaving && since(count).filter(r => r.path === "/api/ai/writer/jobs/edited").length === 1 && !button.disabled,
            { disabledWhileSaving, sent: since(count).length });

        // W7 保存中に直した部分は、保存済みと表示しない（送った時点の版だけが保存されたと案内する）
        saveReply = () => ({ status: 200, body: { id: "13", status: "完了" } });
        saveDelayMs = 500;
        fill(complete);
        count = requests.length;
        button.click();
        await wait(100);
        $("writer-output-title").value = "保存中に直したタイトル";
        await wait(800);
        const sentWhileEditing = since(count).find(r => r.path === "/api/ai/writer/jobs/edited")?.body;
        check("W7 保存中に直した部分は保存済みと表示せず、もう一度保存するよう案内する",
            sentWhileEditing?.title === "本人が直したタイトル" && latest.title === "本人が直したタイトル"
                && status().includes("保存中に直した部分はまだ保存していません") && !status().includes("この版を対象にします")
                && $("writer-output-title").value === "保存中に直したタイトル",
            { sentTitle: sentWhileEditing?.title, status: status() });

        // W8 保存中に何も直さなければ、今までどおり保存済みと表示する
        saveDelayMs = 300;
        fill(complete);
        button.click();
        await wait(600);
        check("W8 保存中に何も直さなければ、保存済みと表示する",
            status().includes("直した下書きを保存しました") && !status().includes("まだ保存していません"),
            { status: status() });
        saveDelayMs = 0;

        // W9 修正版を何回か保存すると、品質管理AIの対象は最後に保存した修正版になる
        let nextId = 20;
        saveReply = () => ({ status: 200, body: { id: String(nextId++), status: "完了" } });
        count = requests.length;
        for (const title of ["1回目の修正", "2回目の修正", "3回目の修正"]) {
            fill({ ...complete, "writer-output-title": title });
            button.click();
            await wait(200);
        }
        const savedTitles = since(count).filter(r => r.path === "/api/ai/writer/jobs/edited").map(r => r.body.title);
        count = requests.length;
        await runQualityAction();
        const qualityLast = since(count).find(r => r.path === "/api/ai/writer/quality");
        check("W9 修正版を3回保存すると、品質管理AIの対象は最後の修正版",
            JSON.stringify(savedTitles) === JSON.stringify(["1回目の修正", "2回目の修正", "3回目の修正"])
                && qualityLast?.body.title === "3回目の修正" && status().includes("下書き番号 22"),
            { savedTitles, quality: qualityLast?.body.title });
    } finally {
        window.fetch = realFetch;
        fill(beforeFields);
        try {
            if (beforeStorage === null) localStorage.removeItem(WRITER_DRAFT_KEY);
            else localStorage.setItem(WRITER_DRAFT_KEY, beforeStorage);
        } catch (error) { /* 保存領域が使えない環境 */ }
    }

    console.table(results);
    console.log(log.join("\n"));
    return results;
})();
