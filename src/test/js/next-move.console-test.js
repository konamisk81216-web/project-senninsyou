// L-1「選んだ収益機会の根拠から、将軍が次の一手を提案する」の模擬試験（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 数秒で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、収益機会の一覧（/api/opportunities）と次の一手（/api/opportunities/{id}/next-move）への通信を、
// このページの中だけで偽物に差し替える。OpenAI は呼ばれず、DB にも書き込まない。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const results = [];
    const check = (name, ok, detail) => results.push({ 試験: name, 判定: ok ? "合格" : "不合格", 詳細: JSON.stringify(detail) });
    const requests = [];

    const base = {
        opportunityType: "note記事", expectedRevenue: 1000, estimatedMinutes: 60, riskLevel: "中", status: "有望",
        notes: "", expectedRevenuePerHour: 1000, comparisonScore: 700, createdAt: null, linkedTaskStatus: "進行中",
        actualRevenue: null, actualExpense: null, actualProfit: null, revenueVariance: null, revenueRecordCount: 0
    };
    const opportunities = [
        { ...base, id: 1, title: "機会A", linkedTaskId: 10, linkedTaskName: "任務A" },
        { ...base, id: 2, title: "機会B", linkedTaskId: null, linkedTaskName: null, linkedTaskStatus: null },
        { ...base, id: 3, title: "機会C", linkedTaskId: 30, linkedTaskName: "任務C", revenueRecordCount: 2,
          actualRevenue: 0, actualExpense: 0, actualProfit: 0, revenueVariance: -1000 }
    ];
    const notice = "提案だけです。AI社員は実行していません。";
    const ok = (id, extra = {}) => ({
        status: "OK", message: "AI将軍の提案です。", notice, recordNote: null,
        facts: [
            { label: "収益機会", value: `機会${id}（opportunity:${id}）` },
            { label: "関連任務", value: "任務未登録" },
            { label: "収益記録", value: "0件" },
            { label: "実売上", value: "未記録" },
            { label: "想定との差", value: "算出不可" }
        ],
        proposal: { nextMove: "小さな記事で反応を確かめる", reasons: [{ text: "記録がまだない", evidenceIds: [`opportunity:${id}`] }], missingInfo: "閲覧数" },
        ...extra
    });
    // 次の一手の偽の返答。機会ごとに差し替える。delay は返答までの時間。
    let nextMoveReply = id => ({ status: 200, body: ok(id), delay: 200 });

    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init = {}) => {
        const path = new URL(typeof input === "string" ? input : input.url, location.href).pathname;
        const method = init.method ?? "GET";
        if (path.startsWith("/api/")) requests.push(`${method} ${path}`);
        if (path === "/api/opportunities" && method === "GET") {
            return new Response(JSON.stringify(opportunities), { status: 200, headers: { "Content-Type": "application/json" } });
        }
        const match = path.match(/^\/api\/opportunities\/(\d+)\/next-move$/);
        if (match && method === "POST") {
            const reply = nextMoveReply(Number(match[1]));
            await wait(reply.delay ?? 0);
            if (reply.networkError) throw new TypeError("Failed to fetch");
            const body = typeof reply.body === "string" ? reply.body : JSON.stringify(reply.body);
            return new Response(body, { status: reply.status, headers: { "Content-Type": "application/json" } });
        }
        if (path.startsWith("/api/")) return new Response("この試験では使わない通信", { status: 500 });
        return realFetch(input, init);
    };

    const list = () => document.getElementById("opportunity-list");
    const card = title => [...list().querySelectorAll(".opportunity-card")].find(item => item.querySelector("h4")?.textContent.includes(title));
    const button = title => card(title)?.querySelector(".next-move-button");
    const output = title => card(title)?.querySelector(".opportunity-next-move");
    const posts = () => requests.filter(r => r.startsWith("POST") && r.endsWith("/next-move"));
    const facts = title => Object.fromEntries([...(output(title)?.querySelectorAll(".next-move-facts > div") ?? [])]
        .map(div => [div.querySelector("span").textContent, div.querySelector("strong").textContent]));
    const text = (title, selector) => output(title)?.querySelector(selector)?.textContent ?? null;

    try {
        await loadOpportunities();
        await wait(50);

        // N1 全部の機会にボタンが出る（任務未登録の機会でも出る）。押すまで結果欄は隠れている
        check("N1 全部の機会に「将軍に次の一手を聞く」が出る（任務未登録でも）。押すまで結果欄は出ない",
            ["機会A", "機会B", "機会C"].every(t => button(t)?.textContent === "🧭 将軍に次の一手を聞く" && output(t)?.hidden === true),
            { buttons: ["機会A", "機会B", "機会C"].map(t => button(t)?.textContent ?? null) });

        // N14 機会Aの返答待ちの間に一覧を描き直す → 今のAのカードは待機中のまま → 返答は今のAだけに出る。要求は1回
        nextMoveReply = id => ({ status: 200, body: ok(id), delay: 800 });
        let before14 = posts().length;
        button("機会A").click();
        await wait(100);
        const oldOutput = output("機会A");
        await loadOpportunities();   // 返答待ちの間に描き直す（古いカードは一覧から外れる）
        await wait(50);
        const redrawnWaiting = { disabled: button("機会A").disabled, message: text("機会A", ".next-move-message"),
            replaced: output("機会A") !== oldOutput && !oldOutput.isConnected };
        await wait(1000);
        check("N14 返答待ちの間に一覧を描き直しても、今のAは待機中のまま、返答は今のAだけに出る。要求は1回",
            redrawnWaiting.replaced && redrawnWaiting.disabled === true && redrawnWaiting.message === "AI将軍が根拠を確かめています…"
                && text("機会A", ".next-move-proposal") === "次の一手：小さな記事で反応を確かめる"
                && !button("機会A").disabled
                && output("機会B").hidden === true && output("機会C").hidden === true
                && !oldOutput.textContent.includes("小さな記事で反応を確かめる")
                && posts().length - before14 === 1,
            { redrawnWaiting, proposal: text("機会A", ".next-move-proposal"), posts: posts().length - before14,
              old: oldOutput.textContent });

        // N15 返答のあとにもう一度描き直しても、Aの返答は今のカードに出たまま。描き直しで送り直さない
        await loadOpportunities();
        await wait(50);
        check("N15 返答のあとに描き直しても、Aの返答は今のカードに出たまま。送り直さない",
            text("機会A", ".next-move-proposal") === "次の一手：小さな記事で反応を確かめる"
                && output("機会B").hidden === true && posts().length - before14 === 1,
            { proposal: text("機会A", ".next-move-proposal"), posts: posts().length - before14 });
        nextMoveReply = id => ({ status: 200, body: ok(id), delay: 200 });

        // N2 1回押すと送信1回。返答待ちの間は押せない。返答のあとは押せる
        let before = posts().length;
        button("機会A").click();
        await wait(50);
        const disabledWhileWaiting = button("機会A").disabled;
        const waitingMessage = text("機会A", ".next-move-message");
        await wait(400);
        check("N2 1回押すと送信1回。返答待ちの間は押せず、返答のあとは押せる",
            posts().length - before === 1 && disabledWhileWaiting && !button("機会A").disabled
                && waitingMessage === "AI将軍が根拠を確かめています…",
            { posts: posts().length - before, disabledWhileWaiting, waitingMessage, after: button("機会A").disabled });

        // N3 サーバーが確定した根拠をそのまま出し、提案・根拠番号・足りない情報・注意を出す
        check("N3 根拠はサーバーの文字をそのまま出し、提案・根拠番号・足りない情報と「提案だけです」を出す",
            facts("機会A")["実売上"] === "未記録" && facts("機会A")["想定との差"] === "算出不可" && facts("機会A")["収益記録"] === "0件"
                && text("機会A", ".next-move-proposal") === "次の一手：小さな記事で反応を確かめる"
                && text("機会A", ".next-move-reason") === "根拠：記録がまだない（opportunity:1）"
                && text("機会A", ".next-move-missing") === "足りない情報：閲覧数"
                && text("機会A", ".next-move-notice") === notice,
            { facts: facts("機会A"), proposal: text("機会A", ".next-move-proposal"), reason: text("機会A", ".next-move-reason") });

        // N4 結果は押したカードにだけ出る
        check("N4 結果は押したカードにだけ出る",
            output("機会A").hidden === false && output("機会B").hidden === true && output("機会C").hidden === true,
            { A: output("機会A").hidden, B: output("機会B").hidden, C: output("機会C").hidden });

        // N5 連打しても送信は1回
        before = posts().length;
        nextMoveReply = id => ({ status: 200, body: ok(id), delay: 300 });
        for (let i = 0; i < 5; i++) button("機会B").click();
        button("機会B").dispatchEvent(new MouseEvent("click"));
        await wait(600);
        check("N5 連打しても送信は1回", posts().length - before === 1, { posts: posts().length - before });

        // N6 根拠の取得に失敗（503・READ_FAILED）→ 案内だけ。根拠も提案も出さない
        nextMoveReply = () => ({ status: 503, delay: 50, body: { status: "READ_FAILED", message: "根拠を取得できませんでした。推測では提案しません。",
            notice, facts: null, proposal: null, recordNote: null } });
        button("機会C").click();
        await wait(250);
        check("N6 根拠の取得に失敗したら、案内だけを出し、根拠と提案は出さない",
            text("機会C", ".next-move-message") === "根拠を取得できませんでした。推測では提案しません。"
                && output("機会C").querySelectorAll(".next-move-facts, .next-move-proposal, .next-move-reason").length === 0
                && !button("機会C").disabled,
            { message: text("機会C", ".next-move-message") });

        // N7 失敗の応答に提案が入っていても出さない（status が OK でなければ出さない）
        nextMoveReply = id => ({ status: 502, delay: 50, body: { ...ok(id), status: "INVALID_ANSWER", message: "AI将軍の返答が決まりに合わなかったため、提案を表示しません。" } });
        button("機会C").click();
        await wait(250);
        check("N7 失敗の応答に提案や根拠が入っていても出さない",
            text("機会C", ".next-move-message").startsWith("AI将軍の返答が決まりに合わなかった")
                && output("機会C").querySelectorAll(".next-move-facts, .next-move-proposal").length === 0,
            { message: text("機会C", ".next-move-message") });

        // N8 通信に失敗 → 案内だけ。ボタンは押せるに戻る。自動で送り直さない
        before = posts().length;
        nextMoveReply = () => ({ networkError: true, delay: 50 });
        button("機会C").click();
        await wait(1500);
        check("N8 通信に失敗したら案内だけを出し、ボタンは押せるに戻る。自動で送り直さない",
            text("機会C", ".next-move-message") === "通信に失敗しました。提案は表示していません。"
                && !button("機会C").disabled && posts().length - before === 1,
            { message: text("機会C", ".next-move-message"), posts: posts().length - before });

        // N9 同じ機会を作成中（409・BUSY）→ 案内だけ
        nextMoveReply = () => ({ status: 409, delay: 50, body: { status: "BUSY", message: "同じ収益機会の提案を作成中です。返答のあとにもう一度押してください。", notice } });
        button("機会C").click();
        await wait(250);
        check("N9 同じ機会を作成中と返ったら、案内だけを出す",
            text("機会C", ".next-move-message").startsWith("同じ収益機会の提案を作成中です")
                && output("機会C").querySelectorAll(".next-move-proposal").length === 0,
            { message: text("機会C", ".next-move-message") });

        // N10 完了記録の保存を確認できなかったとき、提案と一緒にその旨を出す
        nextMoveReply = id => ({ status: 200, delay: 50, body: ok(id, { recordNote: "完了記録の保存を確認できませんでした。" }) });
        button("機会C").click();
        await wait(250);
        check("N10 完了記録の保存を確認できなかったら、提案と一緒にその旨を出す",
            text("機会C", ".next-move-record-note") === "完了記録の保存を確認できませんでした。"
                && text("機会C", ".next-move-proposal") !== null,
            { note: text("機会C", ".next-move-record-note") });

        // N11 AIの文にタグに似た文字があっても、文字として出す
        window.__nextMoveInjected = false;
        nextMoveReply = id => ({ status: 200, delay: 50, body: ok(id, {
            proposal: { nextMove: "<img src=x onerror=\"window.__nextMoveInjected=true\">記事を出す",
                reasons: [{ text: "<b>太字</b>", evidenceIds: ["opportunity:3"] }], missingInfo: "<script>window.__nextMoveInjected=true</script>" } }) });
        button("機会C").click();
        await wait(300);
        check("N11 AIの文にタグに似た文字があっても、文字として出す（要素を作らない）",
            output("機会C").querySelectorAll("img, b, script").length === 0 && window.__nextMoveInjected === false
                && text("機会C", ".next-move-proposal").includes("<img src=x"),
            { proposal: text("機会C", ".next-move-proposal"), injected: window.__nextMoveInjected });

        // N12 提案のあと、承認カードは出ず、AI社員のAPI（/api/ai/）も、次の一手以外の書き込み（POST など）も呼ばれない
        // （司令本部の5秒ごとの読み取り GET /api/command-center などは、この機能と関係なく動くので数えない）
        const otherCalls = requests.filter(r => r.includes(" /api/ai/") || (!r.startsWith("GET ") && !r.endsWith("/next-move")));
        check("N12 承認カードは出ず、AI社員などほかのAPIは呼ばれない",
            document.querySelectorAll(".action-approval").length === 0 && otherCalls.length === 0,
            { approvals: document.querySelectorAll(".action-approval").length, otherCalls });

        // N13 応答がJSONでなければ、案内だけを出す
        nextMoveReply = () => ({ status: 500, delay: 50, body: "<html>Internal Server Error</html>" });
        button("機会B").click();
        await wait(250);
        check("N13 応答が読めなければ、提案を受け取れなかった旨だけを出す",
            text("機会B", ".next-move-message") === "提案を受け取れませんでした（500）。"
                && output("機会B").querySelectorAll(".next-move-proposal").length === 0,
            { message: text("機会B", ".next-move-message") });
    } finally {
        window.fetch = realFetch;
        delete window.__nextMoveInjected;
    }

    console.table(results);
    return results;
})();
