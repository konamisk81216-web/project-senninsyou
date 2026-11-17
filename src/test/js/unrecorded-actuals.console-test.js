// 「収益機会の実績で、記録0件（未記録）を0円として出さない」の模擬試験（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 数秒で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、収益機会の一覧（/api/opportunities）への通信を、このページの中だけで偽物に差し替える。
// OpenAI は呼ばれず、DB にも書き込まない。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const results = [];
    const check = (name, ok, detail) => results.push({ 試験: name, 判定: ok ? "合格" : "不合格", 詳細: JSON.stringify(detail) });
    const yen = value => new Intl.NumberFormat("ja-JP", { style: "currency", currency: "JPY", maximumFractionDigits: 0 }).format(value);

    // サーバー（偽物）が返す収益機会。想定収益はどれも1000円。
    const base = {
        opportunityType: "note記事", expectedRevenue: 1000, estimatedMinutes: 60, riskLevel: "中", status: "有望",
        notes: "", expectedRevenuePerHour: 1000, comparisonScore: 700, createdAt: null, linkedTaskStatus: "進行中"
    };
    const opportunities = [
        // 関連タスクはあるが、収益記録が0件
        { ...base, id: 1, title: "機会A", linkedTaskId: 11, linkedTaskName: "任務A", revenueRecordCount: 0,
          actualRevenue: null, actualExpense: null, actualProfit: null, revenueVariance: null },
        // 収益記録が2件あり、合計が0円
        { ...base, id: 2, title: "機会B", linkedTaskId: 12, linkedTaskName: "任務B", revenueRecordCount: 2,
          actualRevenue: 0, actualExpense: 0, actualProfit: 0, revenueVariance: -1000 },
        // 収益記録が3件あり、金額がある
        { ...base, id: 3, title: "機会C", linkedTaskId: 13, linkedTaskName: "任務C", revenueRecordCount: 3,
          actualRevenue: 1500, actualExpense: 200, actualProfit: 1300, revenueVariance: 500 },
        // タスク未登録
        { ...base, id: 4, title: "機会D", linkedTaskId: null, linkedTaskName: null, linkedTaskStatus: null, revenueRecordCount: 0,
          actualRevenue: null, actualExpense: null, actualProfit: null, revenueVariance: null },
        // 記録はあると返ってきたのに、金額が欠けている（想定外の応答）
        { ...base, id: 5, title: "機会E", linkedTaskId: 15, linkedTaskName: "任務E", revenueRecordCount: 1,
          actualRevenue: null, actualExpense: null, actualProfit: null, revenueVariance: null }
    ];
    // 件数が欠けている・null・不正な値（想定外の応答）。金額は0円と−1000円を入れておき、それを実績として出さないことも確かめる。
    const brokenCounts = [
        ["機会F", "欠落", undefined], ["機会G", "null", null], ["機会H", "負の数", -1],
        ["機会I", "小数", 1.5], ["機会J", "文字の数字", "2"], ["機会K", "文字", "abc"]
    ];
    brokenCounts.forEach(([title, , count], index) => {
        const item = { ...base, id: 100 + index, title, linkedTaskId: 200 + index, linkedTaskName: `任務${title}`,
            actualRevenue: 0, actualExpense: 0, actualProfit: 0, revenueVariance: -1000 };
        if (count !== undefined) item.revenueRecordCount = count;
        opportunities.push(item);
    });
    let reply = () => new Response(JSON.stringify(opportunities), { status: 200, headers: { "Content-Type": "application/json" } });
    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init = {}) => {
        const path = new URL(typeof input === "string" ? input : input.url, location.href).pathname;
        if (path === "/api/opportunities") return reply();
        return realFetch(input, init);
    };

    const list = () => document.getElementById("opportunity-list");
    const card = title => [...list().querySelectorAll(".opportunity-card")]
        .find(item => item.querySelector("h4")?.textContent.includes(title));
    const trace = title => {
        const element = card(title)?.querySelector(".opportunity-trace");
        if (!element) return null;
        const metrics = Object.fromEntries([...element.querySelectorAll(".opportunity-trace-metrics > div")]
            .map(metric => [metric.querySelector("span").textContent, metric.querySelector("strong").textContent]));
        return { task: element.querySelector("p")?.textContent ?? "", metrics };
    };

    try {
        await loadOpportunities();
        await wait(50);

        // U1 関連タスクはあるが記録0件 → 実売上・実利益は「未記録」、想定との差は「算出不可」。0円を出さない
        const a = trace("機会A");
        check("U1 記録0件なら、実売上・実利益は未記録、想定との差は算出不可（0円を出さない）",
            a !== null && a.metrics["実売上"] === "未記録" && a.metrics["実利益"] === "未記録"
                && a.metrics["想定との差"] === "算出不可" && a.task.includes("収益記録 0件")
                && !Object.values(a.metrics).slice(1).some(value => value.includes(yen(0))),
            a);

        // U2 記録はあるが合計0円 → 0円（正しい実績）、想定との差は −1000円
        const b = trace("機会B");
        check("U2 記録2件・合計0円なら、0円の実績として出し、想定との差も出す",
            b !== null && b.metrics["実売上"] === yen(0) && b.metrics["実利益"] === yen(0)
                && b.metrics["想定との差"] === yen(-1000) && b.task.includes("収益記録 2件"),
            b);

        // U3 記録があれば、その金額と件数
        const c = trace("機会C");
        check("U3 記録3件なら、件数と金額をそのまま出す",
            c !== null && c.metrics["実売上"] === yen(1500) && c.metrics["実利益"] === yen(1300)
                && c.metrics["想定との差"] === `+${yen(500)}` && c.task.includes("収益記録 3件"),
            c);

        // U4 タスク未登録 → 実績の欄は出さない（今までどおり）。0円も出さない
        const d = card("機会D");
        check("U4 タスク未登録なら、実績の欄を出さず0円も出さない",
            d !== undefined && trace("機会D") === null && !d.textContent.includes("実売上"),
            { text: d?.textContent });

        // U5 記録ありと返ってきたのに金額が欠けている → 0円で埋めず「取得できません」
        const e = trace("機会E");
        check("U5 記録ありなのに金額が欠けていたら、0円で埋めず取得できませんと出す",
            e !== null && e.metrics["実売上"] === "取得できません" && e.metrics["実利益"] === "取得できません"
                && e.metrics["想定との差"] === "算出不可",
            e);

        // U7〜U12 件数が欠けている・null・不正な値 → 「取得できません」。0件・未記録・0円とは出さず、想定との差も算出不可
        const brokenLabels = { 欠落: "U7", null: "U8", 負の数: "U9", 小数: "U10", 文字の数字: "U11", 文字: "U12" };
        for (const [title, kind] of brokenCounts) {
            const t = trace(title);
            const text = t ? `${t.task} ${Object.values(t.metrics).slice(1).join(" ")}` : "";
            check(`${brokenLabels[kind]} 件数が${kind}なら、取得できませんと出し、0件・未記録・0円・想定との差を出さない`,
                t !== null && t.task.includes("収益記録 取得できません")
                    && t.metrics["実売上"] === "取得できません" && t.metrics["実利益"] === "取得できません"
                    && t.metrics["想定との差"] === "算出不可"
                    && !text.includes("0件") && !text.includes("未記録") && !text.includes(yen(0)) && !text.includes(yen(-1000)),
                t);
        }

        // U13 正しい件数（0件と1件以上）と不正な件数が同じ一覧に並んでも、それぞれの出し方が混ざらない
        const zero = trace("機会A"), one = trace("機会B"), broken = trace("機会G");
        check("U13 正しい0件は未記録、正しい2件は0円の実績、null件数は取得できません（混ざらない）",
            zero?.metrics["実売上"] === "未記録" && one?.metrics["実売上"] === yen(0) && broken?.metrics["実売上"] === "取得できません",
            { zero: zero?.metrics["実売上"], one: one?.metrics["実売上"], broken: broken?.metrics["実売上"] });

        // U6 一覧の取得に失敗 → 失敗の案内だけ。前の金額や0円を出さない
        reply = () => new Response("収益機会の取得に失敗しました。", { status: 500 });
        await loadOpportunities();
        await wait(50);
        check("U6 一覧の取得に失敗したら、失敗の案内だけを出す（0円や前の金額を出さない）",
            list().textContent === "収益機会の取得に失敗しました。" && list().querySelectorAll(".opportunity-card").length === 0,
            { text: list().textContent });
    } finally {
        window.fetch = realFetch;
    }

    console.table(results);
    return results;
})();
