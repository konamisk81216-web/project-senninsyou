// 会話をダッシュボードに統合した画面の模擬試験（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開いた直後（何も操作しない状態）で、開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 約20秒で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、読み上げ・司令本部の巡回（/api/command-center）・報告（/api/report）・AI将軍への送信（/api/ai/command）・
// ライターAI（/api/ai/writer/jobs）・収益機会（/api/opportunities）と次の一手を、このページの中だけで偽物に差し替える。
// OpenAI は呼ばれず、DB にも書き込まない。マイクは使わない（呼ばれたら数えるだけで、許可しない）。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const results = [];
    const check = (name, ok, detail = "") => results.push({ 試験: name, 判定: ok ? "合格" : "不合格", 詳細: typeof detail === "string" ? detail : JSON.stringify(detail) });
    const $ = id => document.getElementById(id);
    const text = id => $(id).textContent;
    const visible = element => Boolean(element) && element.getClientRects().length > 0 && getComputedStyle(element).visibility !== "hidden";
    const panel = $("talk-panel");

    // D1 は、貼り付ける前（読み込みから今まで）に何が起きたかを見る。読み込み時の通信は Resource Timing に残る。
    // AI将軍への送信・次の一手・執筆の開始の宛先への通信がないこと（起動時の読み取り、たとえば直近の下書きの確認は送信ではないので数えない）。
    // 読み込み前から数える仕組み（window.__prespy、試験用のページだけにある。GET 以外の通信・マイク・聞き取りの開始を数える）があれば、それも使う。
    const loadRequests = performance.getEntriesByType("resource").map(entry => new URL(entry.name).pathname);
    const prespy = window.__prespy ?? null;
    const initial = {
        aiRequests: loadRequests.filter(path => path === "/api/ai/command" || path === "/api/ai/writer/jobs" || /\/next-move$/.test(path)),
        prespy: prespy ? { posts: prespy.posts.slice(), mic: prespy.mic, recognition: prespy.recognition } : null,
        voiceSessionActive, voiceListening, commandInFlight,
        visualMic: window.__talk.micActive || window.__talk.micPending, handsFree: typeof handsFreeStream !== "undefined" && handsFreeStream !== null,
        micIndicatorHidden: $("mic-indicator").hidden
    };

    // 読み上げの偽物（音は出さない）。
    window.speechSynthesis.speak = utterance => { setTimeout(() => { utterance.onstart?.({}); setTimeout(() => utterance.onend?.({}), 200); }, 10); };
    window.speechSynthesis.cancel = () => {};
    window.alert = () => {};
    // マイクの偽物：呼ばれたら数えるだけで、許可しない。
    let micCalls = 0;
    if (navigator.mediaDevices) navigator.mediaDevices.getUserMedia = async () => { micCalls++; const error = new Error("denied"); error.name = "NotAllowedError"; throw error; };

    // 通信の偽物。どの通信が何回あったかを記録する。
    const LONG_REPLY = "長い返答の原文です。" + "根拠と手順を順に説明します。".repeat(40) + "（終わり）";
    const WRITING_THEME = "AI社員に仕事を任せる手順 <b>入門</b>";
    const WRITING_AUDIENCE = "1人で事業をする会社員（副業）";
    let reply = "short";          // short・long・writing
    let replyDelay = 200;
    let center = {
        numbers: { articleCount: 3, approvedPosts: 0 },
        money: { totalRevenue: 0, totalProfit: 0, totalWorkMinutes: 0, monthlyCost: 0, totalExpense: 0, revenueRecorded: false, monthlyCostRecorded: false },
        priorityTasks: [{ taskName: "記事を1本書く", priority: "高", status: "進行中", assignedAgent: "ライターAI" }],
        agents: [{ agent: "AI将軍", lastAction: "相談に答える", status: "待機中" }, { agent: "ライターAI", lastAction: "記事の下書きを書く", status: "長時間実行中" }],
        activities: [{ id: "990001", agent: "品質管理AI", action: "公開前の品質を採点する", status: "完了", detail: "72点", startedAt: "2026-11-19 10:00:00.0", durationSeconds: "12" }]
    };
    const report = { available: true, changed: true, signature: "sig-1", unfinishedCount: 2, priorityTasks: [{ taskName: "記事を1本書く", priority: "高" }],
        waitingApprovals: 0, agents: [], recentErrors: [], lastCompleted: { agent: "品質管理AI", action: "採点", detail: "" }, nextAction: "記事を書く",
        usage: { azure: "管理画面で確認", openai: "管理画面で確認" }, lastReportedAt: "2026-11-18 10:00" };
    const writerBodies = [];
    const calls = [];
    const opportunities = [{ id: 6, title: "金の成る木（模擬）", opportunityType: "note記事", expectedRevenue: 3000, estimatedMinutes: 90, riskLevel: "中", status: "有望",
        notes: "", expectedRevenuePerHour: 2000, comparisonScore: 700, createdAt: null, linkedTaskId: null, linkedTaskName: null, linkedTaskStatus: null,
        actualRevenue: null, actualExpense: null, actualProfit: null, revenueVariance: null, revenueRecordCount: 0 }];
    const json = body => new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
        const path = new URL(typeof input === "string" ? input : input.url, location.href).pathname;
        const method = (init?.method ?? "GET").toUpperCase();
        if (path === "/api/command-center") return json(center);
        calls.push(`${method} ${path}`);
        if (path === "/api/report") return json(report);
        if (path === "/api/report/seen") return json({});
        if (path === "/api/ai/command") {
            await wait(replyDelay);
            const summary = reply === "long" ? LONG_REPLY : reply === "writing" ? "執筆を提案します。" : "短い返答です。";
            return json({ summary, revenueInsight: "", performanceDecision: "", marketOpportunity: "", nextTask: "", priority: "", assignedAgent: "",
                ...(reply === "writing" ? { action: "startWriting", actionLabel: "執筆しますか？", actionTheme: WRITING_THEME, actionAudience: WRITING_AUDIENCE }
                    : { action: "", actionLabel: "", actionTheme: "", actionAudience: "" }) });
        }
        if (path === "/api/ai/writer/jobs" && method === "POST") { writerBodies.push(JSON.parse(init.body)); return json({ id: 990077, status: "作成中" }); }
        if (path === "/api/ai/writer/jobs/990077") return json({ id: 990077, status: "作成中" });
        if (path === "/api/opportunities" && method === "GET") return json(opportunities);
        if (/^\/api\/opportunities\/\d+\/next-move$/.test(path) && method === "POST") {
            await wait(150);
            return json({ status: "OK", message: "AI将軍の提案です。", notice: "提案だけです。AI社員は実行していません。", recordNote: null,
                facts: [{ label: "関連任務", value: "任務未登録" }], proposal: { nextMove: "小さく試す", reasons: [{ text: "記録がない", evidenceIds: ["opportunity:6"] }], missingInfo: "反応" } });
        }
        if (path.startsWith("/api/")) return new Response("この試験では使わない通信", { status: 500 });
        return realFetch(input, init);
    };
    const count = key => calls.filter(call => call === key).length;
    const commandPosts = () => count("POST /api/ai/command");
    const writerPosts = () => count("POST /api/ai/writer/jobs");
    const submit = value => { $("talk-command").value = value; $("talk-composer").requestSubmit(); };
    const closeLatestCard = () => { const cards = document.querySelectorAll("#ai-response .action-approval"); cards[cards.length - 1]?.querySelector(".secondary-button")?.click(); };

    try {
        // D1：初期表示では、送信・マイク・聞き取りを始めない。
        check("D1 初期表示：AIへの送信・次の一手の要求なし。音声会話・聞き取り・見た目用のマイク・拍手の待ち受けは始まっていない。マイク使用中の表示なし",
            initial.aiRequests.length === 0 && !initial.voiceSessionActive && !initial.voiceListening && !initial.commandInFlight && !initial.visualMic && !initial.handsFree
            && initial.micIndicatorHidden
            && (initial.prespy === null || (initial.prespy.posts.length === 0 && initial.prespy.mic === 0 && initial.prespy.recognition === 0)),
            initial);

        // D2：入口と構成。左メニューに「会話」はなく、ダッシュボードの上から順に4つの欄。表示する文字入力と音声開始はそれぞれ1つ。
        const zones = ["dash-attention", "talk-panel", "dash-summary", "dash-details"].map(id => $(id));
        const ordered = zones.every((zone, index) => index === 0 || (zones[index - 1].compareDocumentPosition(zone) & Node.DOCUMENT_POSITION_FOLLOWING));
        const commandPanels = [...document.querySelectorAll('[data-page-panel="command"]')];
        const shownTextInputs = commandPanels.flatMap(p => [...p.querySelectorAll('input[type="text"], textarea')]).filter(visible).map(e => e.id);
        const shownVoiceStarts = [...document.querySelectorAll("button")].filter(b => visible(b) && b.textContent.includes("音声会話を開始")).map(b => b.id);
        const ids = [...document.querySelectorAll("[id]")].map(element => element.id);
        const structure = {
            talkNav: document.querySelectorAll('.nav-button[data-page="talk"]').length, talkPanel: document.querySelectorAll('[data-page-panel="talk"]').length,
            zonesOnDashboard: zones.map(zone => zone.dataset.pagePanel), ordered, shown: zones.map(visible),
            shownTextInputs, shownVoiceStarts, legacyInputShown: visible($("command-input")), legacySessionShown: visible($("voice-session-toggle")),
            duplicated: ids.filter((id, index) => ids.indexOf(id) !== index), hq: ["hq-total-revenue", "hq-priority-tasks"].map(id => $(id) !== null)
        };
        check("D2 入口はダッシュボードだけ（左メニューに「会話」なし）。上から「いま確認すること」→会話→変化と収支→詳細。表示する文字入力と「音声会話を開始」はそれぞれ1つ。IDの重複・重複していた数字とタスクの欄なし",
            structure.talkNav === 0 && structure.talkPanel === 0 && structure.zonesOnDashboard.every(v => v === "command") && ordered && structure.shown.every(Boolean)
            && JSON.stringify(shownTextInputs) === JSON.stringify(["talk-command"]) && JSON.stringify(shownVoiceStarts) === JSON.stringify(["talk-session-toggle"])
            && !structure.legacyInputShown && !structure.legacySessionShown && structure.duplicated.length === 0 && structure.hq.every(v => v === false), structure);

        // D3：いま確認すること。取得できない項目・要確認の社員・最優先タスク・前回からの変化を短く出す。報告は自動で確認済みにしない。
        await loadCommandCenter();
        await loadReport();
        await wait(100);
        const attention = { task: text("attn-task-text"), fetch: $("attn-fetch").hidden ? null : text("attn-fetch-text"),
            agents: $("attn-agents").hidden ? null : text("attn-agents-text"), report: !$("attn-report").hidden, approval: !$("attn-approval").hidden };
        $("attn-report-button").click();
        await wait(300);
        const seenAfterChip = count("POST /api/report/seen");
        const keyLines = [...document.querySelectorAll("#report-body .report-key p")].map(p => p.textContent);
        const detailOpen = document.querySelector("#report-body .report-details").open;
        center = { ...center, money: null };
        await loadCommandCenter();
        const fetchFailed = text("attn-fetch-text");
        center = { ...center, money: { totalRevenue: 0, totalProfit: 0, totalWorkMinutes: 0, monthlyCost: 0, totalExpense: 0, revenueRecorded: false, monthlyCostRecorded: false } };
        await loadCommandCenter();
        check("D3 いま確認すること：最優先タスク・要確認の社員・前回からの変化を短く出し、取れない項目は「取得できません」。変化の案内を押しても確認済みにしない。報告は要点（未完了・最優先・確認待ち・エラー）と詳細に分ける",
            attention.task === "記事を1本書く（優先度 高）" && attention.fetch === null && attention.agents === "要確認：ライターAI（長時間実行中）" && attention.report && !attention.approval
            && seenAfterChip === 0 && !$("report-panel").hidden
            && JSON.stringify(keyLines) === JSON.stringify(["未完了のタスク：2件", "最優先：記事を1本書く（高）", "確認待ち：0件", "直近のエラー：なし"]) && detailOpen === false
            && fetchFailed === "取得できません：収支", { attention, seenAfterChip, keyLines, detailOpen, fetchFailed });

        // D4：未記録・0円・取得できないの区別と、数字は1か所だけ。
        const revenueUnrecorded = text("hud-revenue");
        center = { ...center, money: { totalRevenue: 0, totalProfit: 0, totalWorkMinutes: 0, monthlyCost: 0, totalExpense: 0, revenueRecorded: true, monthlyCostRecorded: true } };
        await loadCommandCenter();
        const revenueZero = [text("hud-revenue"), text("hud-ai-cost")];
        center = { ...center, money: null };
        await loadCommandCenter();
        const revenueFailed = text("hud-revenue");
        center = { ...center, money: { totalRevenue: 0, totalProfit: 0, totalWorkMinutes: 0, monthlyCost: 0, totalExpense: 0, revenueRecorded: false, monthlyCostRecorded: false } };
        await loadCommandCenter();
        const revenueLabels = commandPanels.flatMap(p => [...p.querySelectorAll(".hud-number > span")]).filter(visible).map(span => span.textContent);
        check("D4 収支：記録なしは「未記録」、記録があって0円は「0円」、取れないときは「取得できません」。売上・案件利益・費用・作業時間・下書き・投稿は1か所に1つずつ。利益・費用の定義を表示",
            revenueUnrecorded === "未記録" && JSON.stringify(revenueZero) === JSON.stringify(["0円", "0円"]) && revenueFailed === "取得できません"
            && JSON.stringify(revenueLabels) === JSON.stringify(["売上", "案件利益（月々の費用は差引前）", "月々の費用（手入力・OpenAIやAzureの実請求額ではない）", "作業時間", "作成済みの下書き", "承認した投稿"])
            && text("dash-summary").includes("案件利益は「売上−案件の直接経費」") && text("dash-summary").includes("OpenAIやAzureの実請求額ではありません"),
            { revenueUnrecorded, revenueZero, revenueFailed, revenueLabels });

        // D5：送信は1回。長い返答は原文のまま全文を開け、承認欄を押し流さない。
        reply = "long";
        const approvalTopBefore = $("talk-approval").getBoundingClientRect().top - panel.getBoundingClientRect().top;
        const postsBefore = commandPosts();
        submit("長い説明をして");
        $("talk-composer").requestSubmit();   // 続けて押しても、返答待ちなので送らない
        await wait(900);
        const sub = $("talk-sub-ai");
        const clamped = { full: sub.textContent === LONG_REPLY, clamped: sub.classList.contains("is-clamped") && sub.scrollHeight > sub.clientHeight + 1,
            more: visible($("talk-sub-more")) && text("talk-sub-more") === "全文を表示" };
        const approvalTopAfter = $("talk-approval").getBoundingClientRect().top - panel.getBoundingClientRect().top;
        $("talk-sub-more").click();
        await wait(50);
        const expanded = { clamped: sub.classList.contains("is-clamped"), height: sub.clientHeight >= sub.scrollHeight - 1, label: text("talk-sub-more") };
        const historyHasFull = $("ai-response").textContent.includes(LONG_REPLY);
        $("talk-sub-more").click();
        const wide = innerWidth >= 1280;
        check("D5 送信は1回（続けて押しても1回）。長い返答は原文を保ったまま短く表示し「全文を表示」で全文、会話の全文にも原文。PC幅では承認欄の位置が動かない",
            commandPosts() - postsBefore === 1 && clamped.full && clamped.clamped && clamped.more && !expanded.clamped && expanded.height && expanded.label === "短く表示"
            && historyHasFull && (!wide || Math.abs(approvalTopAfter - approvalTopBefore) <= 2),
            { posts: commandPosts() - postsBefore, clamped, expanded, historyHasFull, approvalTopBefore, approvalTopAfter, width: innerWidth });

        // D6：承認。承認待ちは上部と承認欄で目立たせ、テーマ・想定読者を出す。承認前は実行0回、承認後は1回（連打しても1回）。
        reply = "writing";
        submit("記事を書いて");
        await wait(900);
        const pendingView = { attn: !$("attn-approval").hidden, attnText: text("attn-approval-text"), head: visible($("talk-head-approval")),
            pending: $("talk-approval").dataset.pending, theme: text("talk-approval-theme"), audience: text("talk-approval-audience"), tags: $("talk-approval-target").querySelectorAll("b").length,
            cards: document.querySelectorAll(".action-approval").length };
        $("attn-approval-button").click();
        await wait(300);
        const focused = document.activeElement === $("talk-approve");
        const beforeApprove = writerPosts();
        $("talk-approve").click();
        $("talk-approve").click();
        await wait(800);
        const sent = writerBodies.at(-1) ?? {};
        check("D6 承認待ち：上部の「いま確認すること」と承認欄に内容・テーマ・想定読者を文字のまま出す。承認カードは1つ（複製しない）。案内を押すと「実行する」へ移る。承認前は実行0回、承認後は表示した値で1回だけ。完了後は案内を閉じる",
            pendingView.attn && pendingView.attnText.length > 0 && pendingView.head && pendingView.pending === "true" && pendingView.theme === WRITING_THEME
            && pendingView.audience === WRITING_AUDIENCE && pendingView.tags === 0 && pendingView.cards === 1 && focused
            && beforeApprove === 0 && writerPosts() === 1 && sent.theme === WRITING_THEME && sent.audience === WRITING_AUDIENCE
            && $("attn-approval").hidden && !visible($("talk-head-approval")),
            { pendingView, focused, beforeApprove, writerPosts: writerPosts(), sent: { theme: sent.theme, audience: sent.audience } });
        if (typeof writerJobTimer !== "undefined") clearInterval(writerJobTimer);   // 片付け（偽の執筆の進み具合の確認を止める）

        // D7：返答待ちの間にほかの画面へ移って戻る。描画は止まって再開し、書き足した文は残り、送信は1回。以前の「会話」への移動はダッシュボードへ案内する。
        reply = "short";
        replyDelay = 900;
        const navBefore = commandPosts();
        submit("画面を移る前の相談");
        await wait(50);
        $("talk-command").value = "画面を移る前の相談 と、待っている間に書き足した文";
        document.querySelector('.nav-button[data-page="tasks"]').click();
        await wait(200);
        const away = { panelHidden: panel.hidden, running: window.__talk.running, title: text("page-title") };
        document.querySelector('.nav-button[data-page="command"]').click();
        await wait(1100);
        const back = { panelHidden: panel.hidden, running: window.__talk.running, input: $("talk-command").value, posts: commandPosts() - navBefore, ai: text("talk-sub-ai") };
        showPage("tasks");
        await wait(100);
        showPage("talk");
        await wait(300);
        const redirect = { panelHidden: panel.hidden, title: text("page-title"), current: document.querySelector('.nav-button[aria-current="page"]')?.dataset.page };
        replyDelay = 200;
        $("talk-command").value = "";
        check("D7 画面移動後の復帰：ほかの画面では会話の欄を隠して描くのを止め、戻ると再開。待っている間に書き足した文は返答のあとも残り、送信は1回。以前の「会話」への移動はダッシュボードの会話の欄へ",
            away.panelHidden && !away.running && away.title === "タスク管理" && !back.panelHidden && back.running
            && back.input === "画面を移る前の相談 と、待っている間に書き足した文" && back.posts === 1 && back.ai === "短い返答です。"
            && !redirect.panelHidden && redirect.title === "ダッシュボード" && redirect.current === "command",
            { away, back, redirect });

        // D8：左上のロゴは、ほかの画面からダッシュボード（会話の欄を含む）へ戻る。読み込み直さない。
        showPage("revenue");
        await wait(100);
        window.__dashNoReload = "kept";
        document.querySelector(".brand-home").click();
        await wait(200);
        check("D8 左上のロゴ：ほかの画面からダッシュボードへ戻り、会話の欄も出る。読み込み直さない",
            !panel.hidden && text("page-title") === "ダッシュボード" && window.__dashNoReload === "kept" && window.__talk.running,
            { panelHidden: panel.hidden, title: text("page-title"), running: window.__talk.running });

        // D9：L-1（次の一手）は機会レーダーで今までどおり動き、ダッシュボードとの行き来で結果が残る（送り直さない）。
        showPage("opportunities");
        await loadOpportunities();
        await wait(50);
        const nextButton = () => document.querySelector('#opportunity-list .next-move-button[data-opportunity-id="6"]');
        const nextOutput = () => document.querySelector('#opportunity-list .opportunity-next-move[data-opportunity-id="6"]');
        nextButton().click();
        nextButton().click();
        await wait(400);
        const firstView = nextOutput().textContent;
        showPage("command");
        await wait(100);
        showPage("opportunities");
        await loadOpportunities();
        await wait(100);
        check("D9 L-1：次の一手の要求は1回（連打しても1回）、提案を表示。ダッシュボードとの行き来で一覧を描き直しても結果は残り、送り直さない",
            count("POST /api/opportunities/6/next-move") === 1 && firstView.includes("小さく試す") && nextOutput().textContent === firstView && !nextOutput().hidden,
            { posts: count("POST /api/opportunities/6/next-move"), firstView: firstView.slice(0, 60) });
        showPage("command");
        await wait(200);

        // D10：マイクはこの試験の中でも一度も求めていない。この幅で横のはみ出しがない。
        const overflow = document.documentElement.scrollWidth - document.documentElement.clientWidth;
        check("D10 この試験の操作でマイクを一度も求めない。この画面幅で横のはみ出しがない", micCalls === 0 && overflow <= 0, { micCalls, overflow, width: innerWidth });
    } catch (error) {
        check("例外", false, String(error && error.stack || error));
    }

    console.table(results);
    const failedCount = results.filter(result => result.判定 !== "合格").length;
    console.log(failedCount === 0 ? "すべて合格" : `${failedCount}件が不合格`);
    window.__dashRegression = { results, calls };
    return results;
})();
