// 「書き方の決まり」（タスク管理画面）の模擬試験（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 数秒で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、書き方の決まり（/api/writing-rules）への通信を、このページの中だけで偽物に差し替える。
// OpenAI は呼ばれず、DB にも書き込まない。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const results = [];
    const check = (name, ok, detail) => results.push({ 試験: name, 判定: ok ? "合格" : "不合格", 詳細: JSON.stringify(detail) });
    const $ = id => document.getElementById(id);

    // 偽のサーバー。決まりを覚えておき、本物と同じ形で返す。fail に応答を入れると、次の1回だけその応答を返す。
    let rules = [];
    let nextId = 100;
    const requests = [];
    let fail = null;
    let delayMs = 0;
    const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
    const sorted = () => [...rules].sort((a, b) => (b.active - a.active) || (b.id - a.id));
    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init = {}) => {
        const url = new URL(typeof input === "string" ? input : input.url, location.href);
        if (!url.pathname.startsWith("/api/writing-rules")) return realFetch(input, init);
        const method = (init.method ?? "GET").toUpperCase();
        const body = init.body ? JSON.parse(init.body) : null;
        requests.push({ method, path: url.pathname, body });
        if (delayMs) await wait(delayMs);
        if (fail) {
            const response = fail;
            fail = null;
            return response;
        }
        const match = url.pathname.match(/^\/api\/writing-rules\/(\d+)(\/active)?$/);
        if (method === "GET" && url.pathname === "/api/writing-rules") return json(sorted());
        if (method === "POST" && url.pathname === "/api/writing-rules") {
            const rule = { id: nextId++, target: body.target, rule: body.rule, reason: body.reason, source: body.source, active: true };
            rules.push(rule);
            return json({ status: "追加しました", id: rule.id }, 201);
        }
        if (method === "PUT" && match) {
            const rule = rules.find(item => item.id === Number(match[1]));
            if (!rule) return json({ error: "その決まりは見つかりませんでした。", errorCode: "RULE_NOT_FOUND" }, 404);
            if (match[2]) rule.active = body.active;
            else Object.assign(rule, { target: body.target, rule: body.rule, reason: body.reason });
            return json({ status: "ok", id: rule.id });
        }
        return json({ error: "想定外の依頼", errorCode: "TEST" }, 405);
    };

    const list = () => $("writing-rules-list");
    const cards = () => [...list().querySelectorAll(".writing-rule")];
    const card = text => cards().find(item => item.querySelector(".writing-rule-text")?.textContent === text);
    const badges = item => [...item.querySelectorAll(".writing-rule-badge")].map(b => b.textContent);
    const status = () => $("writing-rules-status").textContent;
    const button = (item, label) => [...item.querySelectorAll("button")].find(b => b.textContent === label);
    const settle = async () => { for (let i = 0; i < 40 && $("writing-rule-add").disabled; i++) await wait(25); await wait(80); };
    const submitAdd = () => $("writing-rule-form").requestSubmit();

    try {
        // 試験の間はタスク管理画面を開いておく（表示の確認のため）。
        document.querySelector('[data-open-page="tasks"]')?.click();
        await wait(100);

        // W1 一覧：有効な決まりが先。対象・きっかけの種類・無効の印・きっかけの文を出す
        rules = [
            { id: 1, target: "共通", rule: "古い決まり", reason: "", source: "本人", active: false },
            { id: 2, target: "SNS投稿", rule: "数字と日付を混ぜない", reason: "AIが日付を数字と取り違えた", source: "AIの指摘", active: true }
        ];
        await loadWritingRules();
        const first = cards()[0], second = cards()[1];
        check("W1 一覧は有効な決まりが先で、対象・きっかけの種類・無効の印・きっかけを出す",
            cards().length === 2 && first.querySelector(".writing-rule-text").textContent === "数字と日付を混ぜない"
                && badges(first).join("/") === "SNS投稿/AIの指摘"
                && first.querySelector(".writing-rule-reason")?.textContent === "きっかけ：AIが日付を数字と取り違えた"
                && badges(second).join("/") === "共通/本人のダメ出し/無効" && second.classList.contains("is-inactive")
                && !second.querySelector(".writing-rule-reason")
                && button(first, "無効にする") && button(second, "有効に戻す"),
            cards().map(item => item.textContent));

        // W2 決まりがないとき：案内を出す
        rules = [];
        await loadWritingRules();
        check("W2 決まりがないときは、残し方の案内を出す",
            cards().length === 0 && list().textContent.includes("まだ決まりはありません"), list().textContent);

        // W3 読み込みに失敗（503 の JSON）：読めなかったと出し、「決まりがない」とは出さない
        fail = json({ error: "書き方の決まりのDBを読み書きできませんでした。", errorCode: "DB_UNAVAILABLE" }, 503);
        await loadWritingRules();
        check("W3 一覧が読めないときは「読み込めませんでした」と出し、「まだ決まりはありません」とは出さない",
            list().textContent === "書き方の決まりを読み込めませんでした。" && !list().textContent.includes("まだ決まりはありません"),
            list().textContent);

        // W4 追加：前後の空白を除いて送り、成功したら欄を空にして一覧に出す
        requests.length = 0;
        $("writing-rule-target").value = "note記事";
        $("writing-rule-text").value = "  題名に試した日数を正しく書く  ";
        $("writing-rule-reason").value = " 1週間と書いたが2日だった ";
        $("writing-rule-source").value = "本人";
        submitAdd();
        await settle();
        const posts = requests.filter(r => r.method === "POST");
        check("W4 追加は前後の空白を除いて送り、成功したら欄を空にして一覧に出す",
            posts.length === 1 && JSON.stringify(posts[0].body) === JSON.stringify({ target: "note記事", rule: "題名に試した日数を正しく書く", reason: "1週間と書いたが2日だった", source: "本人" })
                && $("writing-rule-text").value === "" && $("writing-rule-reason").value === ""
                && !!card("題名に試した日数を正しく書く") && status() === "決まりに追加しました。",
            { posts, status: status() });

        // W5 保存を待っている間に、もう一度押しても二重に追加しない
        requests.length = 0;
        delayMs = 200;
        $("writing-rule-text").value = "一文を短くする";
        submitAdd();
        submitAdd();
        $("writing-rule-add").click();
        await settle();
        delayMs = 0;
        check("W5 保存を待っている間に何度押しても、追加の依頼は1回だけ",
            requests.filter(r => r.method === "POST").length === 1 && rules.filter(r => r.rule === "一文を短くする").length === 1,
            requests.map(r => r.method));

        // W6 追加の失敗（400 の JSON）：サーバーの文を出し、入力を残し、ボタンが戻る。自動でやり直さない
        requests.length = 0;
        $("writing-rule-text").value = "失敗する決まり";
        $("writing-rule-reason").value = "残るきっかけ";
        fail = json({ error: "決まりは500文字以内にしてください。", errorCode: "RULE_INVALID" }, 400);
        submitAdd();
        await settle();
        check("W6 追加に失敗したら、サーバーの文を出し、入力を残し、ボタンが戻る（やり直しは1回もしない）",
            status() === "決まりは500文字以内にしてください。" && $("writing-rule-text").value === "失敗する決まり"
                && $("writing-rule-reason").value === "残るきっかけ" && !$("writing-rule-add").disabled
                && requests.filter(r => r.method === "POST").length === 1,
            { status: status(), requests: requests.map(r => r.method) });

        // W7 途中の中継が JSON でない失敗（502 の HTML）を返しても、解析エラーにせず状態を出す
        requests.length = 0;
        fail = new Response("<html>Bad Gateway</html>", { status: 502, headers: { "Content-Type": "text/html" } });
        submitAdd();
        await settle();
        check("W7 JSON でない失敗（502 の HTML）でも、解析エラーにせず HTTP の状態を出し、入力を残す",
            status() === "保存先の応答が正しくありませんでした（HTTP 502）。" && $("writing-rule-text").value === "失敗する決まり",
            status());

        // W8 決まりが空なら送らない
        requests.length = 0;
        $("writing-rule-text").value = "   ";
        submitAdd();
        await settle();
        check("W8 決まりが空なら送らず、入力を求める", requests.length === 0 && status() === "決まりを入力してください。", status());

        // W9 編集：今の値が入った欄を開き、保存すると直した内容を送って一覧を直す
        requests.length = 0;
        const target = card("題名に試した日数を正しく書く");
        button(target, "✎ 編集").click();
        const form = target.querySelector(".writing-rule-edit");
        const [targetSelect] = form.querySelectorAll("select");
        const [ruleText, reasonText] = form.querySelectorAll("textarea");
        const opened = targetSelect.value === "note記事" && ruleText.value === "題名に試した日数を正しく書く" && reasonText.value === "1週間と書いたが2日だった";
        targetSelect.value = "共通";
        ruleText.value = " 試した日数は記録で確かめてから書く ";
        form.requestSubmit();
        await wait(150);
        const puts = requests.filter(r => r.method === "PUT");
        check("W9 編集は今の値を入れて開き、保存すると直した内容を送って一覧を直す",
            opened && puts.length === 1 && /^\/api\/writing-rules\/\d+$/.test(puts[0].path)
                && JSON.stringify(puts[0].body) === JSON.stringify({ target: "共通", rule: "試した日数は記録で確かめてから書く", reason: "1週間と書いたが2日だった" })
                && !!card("試した日数は記録で確かめてから書く") && status() === "決まりを直しました。",
            { opened, puts, status: status() });

        // W10 編集の失敗：サーバーの文を出し、直しかけた内容を残す
        const edited = card("試した日数は記録で確かめてから書く");
        button(edited, "✎ 編集").click();
        const editForm = edited.querySelector(".writing-rule-edit");
        editForm.querySelectorAll("textarea")[0].value = "直しかけの決まり";
        fail = json({ error: "その決まりは見つかりませんでした。", errorCode: "RULE_NOT_FOUND" }, 404);
        editForm.requestSubmit();
        await wait(150);
        check("W10 編集に失敗したら、サーバーの文を出し、直しかけた内容を残す",
            status() === "その決まりは見つかりませんでした。" && edited.querySelector(".writing-rule-edit textarea")?.value === "直しかけの決まり",
            status());
        await loadWritingRules();

        // W11 無効にする・有効に戻す：消さずに切り替える
        requests.length = 0;
        button(card("一文を短くする"), "無効にする").click();
        await wait(150);
        const off = card("一文を短くする");
        const offOk = badges(off).includes("無効") && !!button(off, "有効に戻す") && status() === "決まりを無効にしました。";
        button(off, "有効に戻す").click();
        await wait(150);
        const on = card("一文を短くする");
        const actives = requests.filter(r => r.path.endsWith("/active"));
        check("W11 無効にしても消さずに残り、有効に戻せる",
            offOk && !badges(on).includes("無効") && !!button(on, "無効にする")
                && actives.length === 2 && actives[0].body.active === false && actives[1].body.active === true
                && status() === "決まりを有効に戻しました。",
            { actives, status: status() });

        // W12 決まりの文は、文字としてだけ出す（HTML として解釈しない）
        rules.push({ id: 900, target: "共通", rule: "<img src=x onerror=\"window.__writingRuleXss=1\">", reason: "<b>太字</b>", source: "本人", active: true });
        await loadWritingRules();
        await wait(50);
        const xss = card("<img src=x onerror=\"window.__writingRuleXss=1\">");
        check("W12 決まりの文ときっかけは文字として出し、HTML として動かさない",
            !!xss && !xss.querySelector("img") && !xss.querySelector("b") && window.__writingRuleXss !== 1, xss?.innerHTML.slice(0, 120));

        // W13 削除の依頼は一度も送らない
        check("W13 削除の依頼（DELETE）は一度も送らない", requests.every(r => r.method !== "DELETE"), requests.map(r => r.method));

        // W14 表示：横のはみ出しがない（長い決まりも折り返す）
        rules.push({ id: 901, target: "SNS投稿", rule: "あ".repeat(500), reason: "x".repeat(300), source: "AIの指摘", active: true });
        await loadWritingRules();
        await wait(50);
        const overflow = document.documentElement.scrollWidth - window.innerWidth;
        const box = list().getBoundingClientRect();
        check(`W14 表示（幅${window.innerWidth}）：横のはみ出しがなく、一覧が画面の中に収まる`,
            overflow <= 0 && box.left >= 0 && box.right <= window.innerWidth,
            { overflow, left: Math.round(box.left), right: Math.round(box.right) });
    } finally {
        window.fetch = realFetch;
    }

    console.table(results);
    return results;
})();
