// 会話画面（司令室風）の表示と導線の模擬試験（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 約20秒で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、読み上げ・司令本部の巡回（/api/command-center）・AI将軍への送信（/api/ai/command）・
// 直近の下書き（/api/ai/writer/jobs/latest）・品質管理AI（/api/ai/writer/quality）を、このページの中だけで偽物に差し替える。
// OpenAI は呼ばれず、DB にも書き込まない。マイクは使わない。見た目（色・はみ出し）の最終確認は画像と本人の目視で行う。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const results = [];
    const check = (name, ok, detail = "") => results.push({ 試験: name, 判定: ok ? "合格" : "不合格", 詳細: typeof detail === "string" ? detail : JSON.stringify(detail) });
    const $ = id => document.getElementById(id);
    const text = id => $(id).textContent;
    const panel = document.querySelector('[data-page-panel="talk"]');

    // 読み上げの偽物（音は出さない）。
    const spoken = [];
    window.speechSynthesis.speak = utterance => { spoken.push(utterance.text); setTimeout(() => { utterance.onstart?.({}); setTimeout(() => utterance.onend?.({}), 300); }, 20); };
    window.speechSynthesis.cancel = () => {};

    // 通信の偽物。どの通信が何回あったかを記録する。
    const XSS = '<img src=x onerror="window.__bridgeXss=1">';
    let agents = [
        { agent: "AI将軍", lastAction: "相談に答える", status: "待機中" },
        { agent: "品質管理AI", lastAction: "公開前の品質を採点する", status: "完了" }
    ];
    let activities = [{ id: "990001", agent: "品質管理AI", action: "公開前の品質を採点する", status: "完了", detail: "72点", startedAt: "2026-11-14 10:00:00.0", durationSeconds: "12" }];
    let centerFails = false;
    // 執筆の提案に切り替える（テーマには、文字として出るかを見るためにタグに似た文字も入れる）。
    let proposeWriting = false;
    const WRITING_THEME = "AI社員に仕事を任せる手順 <b>入門</b>";
    const WRITING_AUDIENCE = "1人で事業をする会社員（副業）";
    const writerBodies = [];
    // AI将軍への送信を失敗させる（null：成功、"status"：503、"network"：通信の失敗）。
    let commandFails = null;
    // 送信失敗の知らせ（alert）は画面を止めないよう記録だけする。
    const alerts = [];
    window.alert = message => alerts.push(message);
    const calls = [];
    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
        const path = new URL(typeof input === "string" ? input : input.url, location.href).pathname;
        const method = (init?.method ?? "GET").toUpperCase();
        if (path === "/api/command-center") {
            if (centerFails) return new Response("down", { status: 503 });
            return new Response(JSON.stringify({ numbers: null, money: null, priorityTasks: null, agents, activities }), { headers: { "Content-Type": "application/json" } });
        }
        calls.push(`${method} ${path}`);
        if (path === "/api/ai/command") {
            await wait(200);
            // 送信の失敗：サーバーの失敗（503）と、通信そのものの失敗（届かない）。
            if (commandFails === "status") return new Response("unavailable", { status: 503 });
            if (commandFails === "network") throw new TypeError("Failed to fetch");
            return new Response(JSON.stringify({
                summary: "偽の返答です。品質管理AIで採点できます。", revenueInsight: "", performanceDecision: "", marketOpportunity: "",
                nextTask: "", priority: "", assignedAgent: "",
                ...(proposeWriting
                    ? { action: "startWriting", actionLabel: "執筆しますか？", actionTheme: WRITING_THEME, actionAudience: WRITING_AUDIENCE }
                    : { action: "runQuality", actionLabel: "採点しますか？", actionTheme: "", actionAudience: "" })
            }), { headers: { "Content-Type": "application/json" } });
        }
        if (path === "/api/ai/writer/jobs" && method === "POST") {
            writerBodies.push(JSON.parse(init.body));
            return new Response(JSON.stringify({ id: 990077, status: "作成中" }), { headers: { "Content-Type": "application/json" } });
        }
        if (path === "/api/ai/writer/jobs/990077") {
            return new Response(JSON.stringify({ id: 990077, status: "作成中" }), { headers: { "Content-Type": "application/json" } });
        }
        if (path === "/api/ai/writer/jobs/latest") {
            return new Response(JSON.stringify({ id: 9, status: "完了", title: "偽の記事", theme: "t", audience: "a", price: "未定", freeSection: "f", paidSection: "p", salesDescription: "s" }), { headers: { "Content-Type": "application/json" } });
        }
        if (path === "/api/ai/writer/quality") {
            await wait(300);
            return new Response(JSON.stringify({ score: 70, verdict: "偽の判定", issues: [], improvements: [] }), { headers: { "Content-Type": "application/json" } });
        }
        return realFetch(input, init);
    };
    const count = key => calls.filter(call => call === key).length;
    const qualityPosts = () => count("POST /api/ai/writer/quality");
    const commandPosts = () => count("POST /api/ai/command");
    const writerPosts = () => count("POST /api/ai/writer/jobs");
    const ask = async () => {
        $("command-input").value = "採点して";
        $("command-button").click();
        await wait(900);
    };

    try {
        // B1：画面の構成。上部・左・中央・右・下部の要素があり、IDの重複がない。
        const ids = [...document.querySelectorAll("[id]")].map(element => element.id);
        const duplicated = ids.filter((id, index) => ids.indexOf(id) !== index);
        const parts = ["talk-head-state", "talk-agents", "talk-orb", "talk-haru-state", "talk-gaku-state", "talk-log", "talk-sub-user", "talk-sub-ai",
            "talk-session-toggle", "talk-command", "talk-send", "talk-replay", "talk-approval", "talk-head-approval", "talk-fetch-state"];
        const missing = parts.filter(id => !panel.contains($(id)));
        check("B1 画面の構成：上部の状態・AI社員・球体・ハル・ガク・ログ・字幕・開始ボタン・文字入力・承認欄が会話画面にある。IDの重複なし",
            duplicated.length === 0 && missing.length === 0, { duplicated, missing });

        await loadCommandCenter();
        showPage("talk");
        await wait(400);

        // B2：上部の会話の状態は、app.js の状態を読むだけ。
        const seen = {};
        for (const state of ["listening", "thinking", "error", "idle"]) {
            setVoiceState(state);
            await wait(350);
            seen[state] = [text("talk-head-state"), panel.dataset.mode];
        }
        check("B2 上部の会話の状態：聞き取り中・思考中・エラー・待機中を表示し、印の色の区分も合わせる",
            JSON.stringify(seen) === JSON.stringify({ listening: ["聞き取り中", "listening"], thinking: ["思考中", "thinking"], error: ["エラー", "error"], idle: ["待機中", "idle"] }), seen);

        // B3：ハル・ガクの視点。読み上げている発言の話者だけ「読み上げ中」。
        setDemoSubtitle(null, "事業の視点の発言", "ハル");
        setVoiceState("speaking");
        await wait(350);
        const haru = [text("talk-haru-state"), text("talk-gaku-state"), $("talk-haru-state").parentElement.classList.contains("is-speaking"), panel.dataset.speaker];
        setDemoSubtitle(null, "技術の視点の発言", "ガク");
        await wait(350);
        const gaku = [text("talk-haru-state"), text("talk-gaku-state"), $("talk-gaku-state").parentElement.classList.contains("is-speaking"), panel.dataset.speaker];
        setDemoSubtitle(null, "報告です", "");
        setVoiceState("idle");
        await wait(350);
        const none = [text("talk-haru-state"), text("talk-gaku-state"), document.querySelectorAll(".bridge-persona.is-speaking").length];
        const color = id => getComputedStyle($(id)).color;
        setDemoSubtitle(null, "x", "ハル"); await wait(50);
        const haruColor = color("talk-sub-ai-who");
        setDemoSubtitle(null, "x", "ガク"); await wait(50);
        const gakuColor = color("talk-sub-ai-who");
        setDemoSubtitle(null, "", "");
        check("B3 ハル・ガク：読み上げ中の話者だけ強調し、話者のない文では両方「待機」。字幕の話者色はハル橙・ガク緑のまま",
            JSON.stringify(haru) === JSON.stringify(["読み上げ中", "待機", true, "ハル"]) && JSON.stringify(gaku) === JSON.stringify(["待機", "読み上げ中", true, "ガク"])
            && JSON.stringify(none) === JSON.stringify(["待機", "待機", 0]) && haruColor === "rgb(255, 181, 71)" && gakuColor === "rgb(62, 232, 181)",
            { haru, gaku, none, haruColor, gakuColor });

        // B4：記録の取得の区別（取得済み・一部取得できない・取得できない・記録なし）。架空の数字を出さない。
        await loadCommandCenter();
        const ok = [text("talk-fetch-state"), document.querySelectorAll("#talk-agents .talk-agent").length, document.querySelectorAll("#talk-log .talk-log-row").length];
        const keep = activities;
        activities = null;
        await loadCommandCenter();
        const partial = [text("talk-fetch-state"), text("talk-log")];
        centerFails = true;
        await loadCommandCenter();
        const failed = [text("talk-fetch-state"), text("talk-agents"), text("talk-log"), $("talk-fetch-state").dataset.fetch];
        centerFails = false;
        const keepAgents = agents;
        agents = []; activities = [];
        await loadCommandCenter();
        const empty = [text("talk-agents"), text("talk-log")];
        agents = keepAgents; activities = keep;
        await loadCommandCenter();
        const panelText = panel.textContent;
        check("B4 記録の取得：取得済み（時刻）・一部取得できません・取得できません・記録なしを分けて出す。会話画面に金額・進捗率は出さない",
            /^取得済み（\d\d:\d\d:\d\d）$/.test(ok[0]) && ok[1] === 2 && ok[2] === 1
            && JSON.stringify(partial) === JSON.stringify(["一部取得できません", "取得できません"])
            && JSON.stringify(failed) === JSON.stringify(["取得できません", "取得できません", "取得できません", "failed"])
            && JSON.stringify(empty) === JSON.stringify(["AI社員の記録がありません。", "まだ実行記録がありません。"])
            && !/[0-9]+\s*%|円|売上|収益/.test(panelText), { ok, partial, failed, empty });

        // B5：動的な文字列は文字として出す（HTMLとして解釈しない）。
        window.__bridgeXss = 0;
        agents = [{ agent: XSS, lastAction: XSS, status: "待機中" }];
        activities = [{ id: "990002", agent: XSS, action: XSS, status: "完了", detail: XSS, startedAt: "2026-11-14 10:01:00.0", durationSeconds: "" }];
        await loadCommandCenter();
        setDemoSubtitle(XSS, XSS, "");
        await wait(300);
        const injected = panel.querySelectorAll("img").length;
        check("B5 会話文・社員名・ログに含まれるタグは文字として出し、要素として作らない",
            injected === 0 && window.__bridgeXss === 0 && text("talk-sub-ai") === XSS && $("talk-agents").textContent.includes(XSS), { injected, xss: window.__bridgeXss });
        agents = keepAgents; activities = keep;
        await loadCommandCenter();
        setDemoSubtitle("", "", "");

        // B6：承認待ちがないときの表示。
        check("B6 承認待ちがないとき：「承認待ちの操作はありません」と出し、ボタンは出さない",
            text("talk-approval-text") === "現在、承認待ちの操作はありません。" && $("talk-approval-buttons").hidden && $("talk-approval").dataset.pending === "false" && $("talk-head-approval").hidden,
            [text("talk-approval-text"), $("talk-approval-buttons").hidden]);

        // B7：AI将軍の提案が届くと、会話画面に承認欄が出る。承認前はAI社員を実行しない。
        await ask();
        const description = typeof ACTION_DESCRIPTIONS === "object" ? ACTION_DESCRIPTIONS.runQuality : "";
        const pendingView = { pending: $("talk-approval").dataset.pending, text: text("talk-approval-text"), buttons: !$("talk-approval-buttons").hidden, head: !$("talk-head-approval").hidden, title: text("talk-approval-title") };
        await wait(1200);
        check("B7 提案が届くと承認欄に操作の説明と「実行する」「やめる」を出し、上部にも承認待ちの案内を出す。送信は1回、承認前は品質管理AIを実行しない。承認カードを会話画面に複製しない",
            commandPosts() === 1 && pendingView.pending === "true" && pendingView.text === description && pendingView.buttons && pendingView.head && pendingView.title === "あなたの承認が必要です" && $("talk-approval-target").hidden && qualityPosts() === 0
            && document.querySelectorAll("#ai-response .action-approval").length === 1 && panel.querySelectorAll(".action-approval").length === 0,
            { commandPosts: commandPosts(), pendingView, qualityPosts: qualityPosts() });

        // B8：会話画面の「実行する」は、既存の承認カードの処理を1回だけ呼ぶ（連打しても1回）。
        $("talk-approve").click();
        $("talk-approve").click();
        await wait(50);
        const during = { approved: $("talk-approval").dataset.approved, disabled: $("talk-approve").disabled, text: text("talk-approval-text"), title: text("talk-approval-title") };
        await wait(1200);
        const cardStatus = document.querySelector("#ai-response .action-approval .action-approval-status")?.textContent ?? "";
        check("B8 「実行する」：既存の承認カードと同じ処理で品質管理AIを1回だけ実行（連打しても1回）。完了後は承認欄と上部の案内を閉じ、承認カードに結果",
            qualityPosts() === 1 && during.approved === "true" && during.disabled && during.text.startsWith("承認しました。") && during.title === "承認しました（実行中）" && text("talk-approval-title") === "承認が必要な操作"
            && text("talk-approval-text") === "現在、承認待ちの操作はありません。" && $("talk-approval-buttons").hidden && $("talk-head-approval").hidden && cardStatus.includes("70点"),
            { qualityPosts: qualityPosts(), during, cardStatus });

        // B9：「やめる」は既存の承認カードの「やめる」と同じ（カードを閉じ、実行しない）。
        const cardsBefore = document.querySelectorAll("#ai-response .action-approval").length;
        await ask();
        const cardsWithNew = document.querySelectorAll("#ai-response .action-approval").length;
        $("talk-decline").click();
        await wait(300);
        check("B9 「やめる」：新しい承認カードを閉じ、品質管理AIは実行しない。承認欄は待ちなしに戻る",
            commandPosts() === 2 && cardsWithNew === cardsBefore + 1 && document.querySelectorAll("#ai-response .action-approval").length === cardsBefore
            && qualityPosts() === 1 && text("talk-approval-text") === "現在、承認待ちの操作はありません。",
            { commandPosts: commandPosts(), cardsBefore, cardsWithNew, after: document.querySelectorAll("#ai-response .action-approval").length, qualityPosts: qualityPosts() });

        // B10：「承認カードを開く」はダッシュボードへ移り、承認カードを見える位置に出す（実行はしない）。
        await ask();
        $("talk-approval-open").click();
        await wait(600);
        const cards = document.querySelectorAll("#ai-response .action-approval");
        const lastCard = cards[cards.length - 1].getBoundingClientRect();
        check("B10 「承認カードを開く」：ダッシュボードへ移り、いちばん新しい承認カードが画面内に見える。実行はしない",
            panel.hidden && !document.querySelector('[data-page-panel="command"]').hidden && lastCard.top < innerHeight && lastCard.bottom > 0 && qualityPosts() === 1,
            { top: lastCard.top, bottom: lastCard.bottom, innerHeight, qualityPosts: qualityPosts() });
        cards[cards.length - 1].querySelector(".secondary-button").click();   // 片付け（やめる）
        await wait(100);

        // B11：会話画面の文字の入力は、ダッシュボードの既存の入力欄と「将軍と話す」を使う（1発言につき1回。返答待ちの間は送らない）。
        showPage("talk");
        await wait(300);
        const postsBefore = commandPosts();
        $("talk-command").value = "";
        $("talk-composer").requestSubmit();
        await wait(100);
        const emptyCase = { posts: commandPosts() - postsBefore, note: text("talk-composer-note") };
        $("talk-command").value = "会話画面から送る";
        $("talk-composer").requestSubmit();
        $("talk-composer").requestSubmit();   // 続けて押しても、2回目は返答待ちなので送らない
        await wait(50);
        const sentCase = { posts: commandPosts() - postsBefore, handedOff: $("command-input").value, talkInput: $("talk-command").value, userLine: text("talk-sub-user") };
        $("talk-command").value = "返答待ちの間の入力";
        $("talk-composer").requestSubmit();
        await wait(50);
        const busyCase = { posts: commandPosts() - postsBefore, note: text("talk-composer-note"), kept: $("talk-command").value, dashboard: $("command-input").value };
        await wait(1200);
        // 返答が届いても、待っている間に書き換えた文は消さない。
        const repliedCase = { posts: commandPosts() - postsBefore, kept: $("talk-command").value, note: text("talk-composer-note"), dashboard: $("command-input").value };
        const cardsAfterSend = document.querySelectorAll("#ai-response .action-approval");
        cardsAfterSend[cardsAfterSend.length - 1]?.querySelector(".secondary-button")?.click();   // 片付け（やめる）
        $("talk-command").value = "";
        check("B11 文字の入力：空なら送らず案内。入力すると既存の入力欄と「将軍と話す」で1回だけ送る（連続しても1回）。返答待ちの間は送らず、送った文も待っている間に書き換えた文も、返答のあとまで残す",
            emptyCase.posts === 0 && emptyCase.note === "相談や指示を入力してください。"
            && sentCase.posts === 1 && sentCase.handedOff === "会話画面から送る" && sentCase.talkInput === "会話画面から送る" && sentCase.userLine === "会話画面から送る"
            && busyCase.posts === 1 && busyCase.note === "AI将軍の返答を待っています。返答のあとに送ってください。" && busyCase.kept === "返答待ちの間の入力" && busyCase.dashboard === "会話画面から送る"
            && repliedCase.posts === 1 && repliedCase.kept === "返答待ちの間の入力" && repliedCase.note === "" && repliedCase.dashboard === ""
            && panel.hidden === false && qualityPosts() === 1,
            { emptyCase, sentCase, busyCase, repliedCase });
        await wait(300);

        // B17：送信がサーバーの失敗（503）で終わったら、会話画面の入力欄に原文を残し、直して送り直せる。自動では送り直さない。
        // 送り直して成功したら、入力欄は空になる（1発言につき1回）。タグに似た文字は文字のまま扱う。
        showPage("talk");
        await wait(200);
        const failText = "失敗する発言 <b>太字</b>";
        const failBefore = commandPosts();
        alerts.length = 0;
        commandFails = "status";
        $("talk-command").value = failText;
        $("talk-composer").requestSubmit();
        await wait(50);
        const failWaiting = { input: $("talk-command").value, note: text("talk-composer-note") };
        await wait(800);
        const failedSend = { posts: commandPosts() - failBefore, input: $("talk-command").value, note: text("talk-composer-note"), alerts: alerts.length,
            state: typeof currentVoiceState !== "undefined" ? currentVoiceState : null, sendEnabled: !$("command-button").disabled, tags: $("talk-composer-note").querySelectorAll("*").length };
        await wait(1500);
        const noAutoRetry = commandPosts() - failBefore;
        commandFails = null;
        $("talk-command").value = failText + "（直した）";
        $("talk-composer").requestSubmit();
        await wait(1000);
        const resent = { posts: commandPosts() - failBefore, input: $("talk-command").value, note: text("talk-composer-note"), userLine: text("talk-sub-user") };
        const cardsAfterResend = document.querySelectorAll("#ai-response .action-approval");
        cardsAfterResend[cardsAfterResend.length - 1]?.querySelector(".secondary-button")?.click();   // 片付け（やめる）
        check("B17 通信失敗（503）：会話画面の入力欄に原文が残り、直して送り直せる。自動で送り直さない。送り直しは1回だけで、成功すると欄は空になる",
            failWaiting.input === failText && failWaiting.note === "送信しました。AI将軍の返答を待っています。"
            && failedSend.posts === 1 && failedSend.input === failText && failedSend.note === "送信できませんでした。入力欄の文を直して、もう一度送れます。"
            && failedSend.alerts === 1 && failedSend.state === "error" && failedSend.sendEnabled && failedSend.tags === 0
            && noAutoRetry === 1
            && resent.posts === 2 && resent.input === "" && resent.note === "" && resent.userLine === failText + "（直した）",
            { failWaiting, failedSend, noAutoRetry, resent });
        await wait(300);

        // B18：通信そのものが失敗したとき。待っている間に書き足した文は上書きせず、送れなかった原文を案内に文字として出す。
        // 待っている間に欄を空にしていた場合は、送れなかった原文を欄に戻す。
        const netBefore = commandPosts();
        alerts.length = 0;
        commandFails = "network";
        const original = "元の相談 <i>斜体</i>";
        $("talk-command").value = original;
        $("talk-composer").requestSubmit();
        await wait(50);
        const talkInput = $("talk-command");
        talkInput.value = original + " と、待っている間に書き足した文";
        talkInput.dispatchEvent(new Event("input", { bubbles: true }));
        await wait(800);
        const edited = { posts: commandPosts() - netBefore, input: talkInput.value, note: text("talk-composer-note"), alerts: alerts.length,
            tags: $("talk-composer-note").querySelectorAll("*").length };
        talkInput.value = original;
        $("talk-composer").requestSubmit();
        await wait(50);
        talkInput.value = "";
        talkInput.dispatchEvent(new Event("input", { bubbles: true }));
        await wait(800);
        const cleared = { posts: commandPosts() - netBefore, input: talkInput.value, note: text("talk-composer-note") };
        commandFails = null;
        talkInput.value = "";
        check("B18 通信失敗（届かない）：待っている間に書き足した文は上書きせず、送れなかった原文を案内に文字のまま出す。欄を空にしていたら原文を戻す。1発言につき1回だけ送る",
            edited.posts === 1 && edited.input === original + " と、待っている間に書き足した文"
            && edited.note === `送信できませんでした。入力中の文はそのままです。送れなかった文：「${original}」` && edited.alerts === 1 && edited.tags === 0
            && cleared.posts === 2 && cleared.input === original && cleared.note === "送信できませんでした。送れなかった文を入力欄に戻しました。直して、もう一度送れます。",
            { edited, cleared });
        await wait(300);

        // B19：返答待ちの間にダッシュボードへ移り、その入力欄を空にしてから 503 になった場合。
        // 結果は既存の送信処理が知らせる成功・失敗で判断する（ダッシュボードの入力欄が空でも、失敗は失敗）。会話画面の原文は残る。
        showPage("talk");
        await wait(200);
        const dashBefore = commandPosts();
        alerts.length = 0;
        commandFails = "status";
        const dashText = "ダッシュボードへ移る前の相談";
        const results19 = [];
        const onResult = event => results19.push({ ok: event.detail.ok, command: event.detail.command });
        window.addEventListener("senninsyou:command-result", onResult);
        $("talk-command").value = dashText;
        $("talk-composer").requestSubmit();
        await wait(30);
        showPage("command");
        $("command-input").value = "";
        $("command-input").dispatchEvent(new Event("input", { bubbles: true }));
        const duringWait = { inFlight: typeof commandInFlight !== "undefined" && commandInFlight, dashboard: $("command-input").value };
        await wait(800);
        window.removeEventListener("senninsyou:command-result", onResult);
        showPage("talk");
        await wait(100);
        const dashFailed = { posts: commandPosts() - dashBefore, input: $("talk-command").value, note: text("talk-composer-note"), alerts: alerts.length,
            results: results19, dashboard: $("command-input").value };
        commandFails = null;
        $("talk-command").value = "";
        check("B19 返答待ちにダッシュボードへ移り、その入力欄を空にしてから503：送信処理の知らせ（失敗）で判断し、会話画面に原文が残る。送信は1回",
            duringWait.inFlight && duringWait.dashboard === ""
            && dashFailed.posts === 1 && dashFailed.input === dashText && dashFailed.note === "送信できませんでした。入力欄の文を直して、もう一度送れます。"
            && dashFailed.alerts === 1 && dashFailed.results.length === 1 && dashFailed.results[0].ok === false && dashFailed.results[0].command === dashText,
            { duringWait, dashFailed });
        await wait(300);

        // B12：「もう一度聞く」は既存のボタンを1回押すのと同じ。出ているかどうかも既存のボタンに合わせる。
        showPage("talk");
        await wait(300);
        const readButton = $("voice-read-button");
        let delegated = 0;
        const spy = () => { delegated++; };
        readButton.addEventListener("click", spy);
        const visible = [readButton.hidden, $("talk-replay").hidden];
        spoken.length = 0;
        $("talk-replay").click();
        await wait(100);
        readButton.removeEventListener("click", spy);
        readButton.hidden = true;
        await wait(50);
        const hiddenAfter = $("talk-replay").hidden;
        readButton.hidden = visible[0];
        await wait(50);
        setVoiceState("idle");
        check("B12 「もう一度聞く」：既存のボタンを1回押す（読み上げ1回）。既存のボタンが隠れると会話画面のボタンも隠れる",
            visible[0] === false && visible[1] === false && delegated === 1 && spoken.length >= 1 && hiddenAfter === true,
            { visible, delegated, spoken: spoken.length, hiddenAfter });

        // B13：会話画面の「音声会話を開始」は既存のボタンを1回押す（イベントの二重登録がない）。
        const sessionButton = $("voice-session-toggle");
        let sessionClicks = 0;
        const sessionSpy = () => { sessionClicks++; };
        sessionButton.addEventListener("click", sessionSpy);
        commandInFlight = true;   // 返答待ちの間は会話を始めない、という既存の決まりで、実際には会話を始めずに確かめる。
        $("talk-session-toggle").click();
        await wait(50);
        commandInFlight = false;
        sessionButton.removeEventListener("click", sessionSpy);
        check("B13 「音声会話を開始」：既存のボタンを1回だけ押す。既存の決まり（返答待ちの間は始めない）もそのまま",
            sessionClicks === 1 && !voiceSessionActive && text("talk-session-toggle") === sessionButton.textContent, { sessionClicks });

        // B15：執筆の承認欄に、承認の対象（テーマ・想定読者）を出す。承認前は実行0回、承認後は表示した内容で1回だけ実行する。
        showPage("talk");
        await wait(200);
        proposeWriting = true;
        await ask();
        proposeWriting = false;
        const shownTarget = { visible: !$("talk-approval-target").hidden, theme: text("talk-approval-theme"), audience: text("talk-approval-audience"),
            tags: $("talk-approval-target").querySelectorAll("b").length, pending: $("talk-approval").dataset.pending };
        const writingCards = document.querySelectorAll("#ai-response .action-approval");
        const cardDetail = writingCards[writingCards.length - 1].textContent;
        await wait(1200);
        const beforeApprove = writerPosts();
        $("talk-approve").click();
        $("talk-approve").click();
        await wait(800);
        const sent = writerBodies.at(-1) ?? {};
        check("B15 執筆の承認欄にテーマ・想定読者を文字のまま出す（承認カードと同じ値）。承認前は実行0回、承認後は表示したテーマ・想定読者で1回だけ実行（連打しても1回）。完了後は対象の表示を閉じる",
            shownTarget.visible && shownTarget.theme === WRITING_THEME && shownTarget.audience === WRITING_AUDIENCE && shownTarget.tags === 0 && shownTarget.pending === "true"
            && cardDetail.includes(`テーマ：${WRITING_THEME}`) && cardDetail.includes(`想定読者：${WRITING_AUDIENCE}`)
            && beforeApprove === 0 && writerPosts() === 1 && sent.theme === shownTarget.theme && sent.audience === shownTarget.audience
            && $("talk-approval-target").hidden && text("talk-approval-theme") === "" && text("talk-approval-text") === "現在、承認待ちの操作はありません。",
            { shownTarget, beforeApprove, writerPosts: writerPosts(), sent: { theme: sent.theme, audience: sent.audience } });
        if (typeof writerJobTimer !== "undefined") clearInterval(writerJobTimer);   // 片付け（偽の執筆の進み具合の確認を止める）

        // B16：左上のロゴは、押すとホーム（ダッシュボード）へ戻る。ページは読み込み直さない（会話の状態などはそのまま）。
        showPage("talk");
        await wait(200);
        window.__bridgeNoReload = "kept";
        const logo = document.querySelector(".brand-home");
        logo.click();
        await wait(200);
        const home = { href: logo.getAttribute("href"), label: logo.getAttribute("aria-label"), talkHidden: panel.hidden,
            commandShown: !document.querySelector('[data-page-panel="command"]').hidden, current: document.querySelector(".nav-button[aria-current=\"page\"]")?.dataset.page,
            title: text("page-title"), kept: window.__bridgeNoReload };
        check("B16 左上のロゴ：押すとホーム（ダッシュボード）へ戻る。読み込み直さない。リンク先は /",
            home.href === "/" && home.label === "千人将のホームへ戻る" && home.talkHidden && home.commandShown && home.current === "command" && home.title === "ダッシュボード" && home.kept === "kept", home);
        showPage("talk");
        await wait(300);

        // B14：この幅で、会話画面に横のはみ出しがない（スマートフォン幅は画像で確認する）。
        const overflow = { doc: document.documentElement.scrollWidth - document.documentElement.clientWidth, panel: panel.scrollWidth - panel.clientWidth };
        check("B14 横のはみ出しがない（この試験の画面幅）", overflow.doc <= 0 && overflow.panel <= 0, { ...overflow, width: innerWidth });
    } catch (error) {
        check("例外", false, String(error && error.stack || error));
    }

    console.table(results);
    const failedCount = results.filter(result => result.判定 !== "合格").length;
    console.log(failedCount === 0 ? "すべて合格" : `${failedCount}件が不合格`);
    window.__bridgeRegression = { results, calls };
    return results;
})();
