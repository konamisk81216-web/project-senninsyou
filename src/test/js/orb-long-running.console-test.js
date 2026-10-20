// 長時間実行中のAI社員がいるときのオーブ表示の回帰テスト（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 約20秒で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、読み上げと司令本部の巡回（/api/command-center）を、このページの中だけで偽物に差し替える。
// 巡回の返答は、サーバーが画面向けに変換したあとの状態（作業中・長時間実行中・待機中・エラー）を返す。
// OpenAI は呼ばれず、DB にも書き込まない。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const results = [];
    const spoken = [];

    window.speechSynthesis.speak = utterance => {
        setTimeout(() => {
            spoken.push(utterance.text);
            utterance.onstart?.({});
            setTimeout(() => utterance.onend?.({}), 50);
        }, 20);
    };
    window.speechSynthesis.cancel = () => {};

    let rows = [];
    let agents = [];
    // true の間は、司令本部の取得そのものが失敗する（サーバーに届かない・503）。
    let failing = false;
    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
        const path = new URL(typeof input === "string" ? input : input.url, location.href).pathname;
        if (path === "/api/command-center") {
            if (failing) return new Response("Service Unavailable", { status: 503 });
            return new Response(JSON.stringify({ numbers: null, money: null, priorityTasks: null,
                agents, activities: rows }), { headers: { "Content-Type": "application/json" } });
        }
        return realFetch(input, init);
    };

    let nextId = 810000;
    const record = (agent, action, status, detail = "", seconds = "") =>
        ({ id: String(nextId++), agent, action, status, detail, startedAt: "2026-10-19 10:00:00.0", durationSeconds: seconds });
    const agent = (name, status, lastAction = "作業") => ({ agent: name, lastAction, status });
    const orb = () => ({
        hud: document.getElementById("hud-orb").dataset.voiceState,
        hudLabel: document.getElementById("hud-orb-label").textContent,
        demo: document.getElementById("demo-orb").dataset.voiceState,
        demoLabel: document.getElementById("demo-state").textContent
    });
    const poll = async times => { for (let i = 0; i < times; i++) await loadCommandCenter(); };
    const realRunners = { ...ACTION_RUNNERS };

    const reset = async () => {
        if (typeof pendingReports !== "undefined") {
            pendingReports = [];
            reportRetryAt = 0;
            reportSpeaking = false;
            reportedActivityIds = null;
            if (typeof reportStartFailures !== "undefined") reportStartFailures = 0;
        }
        demoRunningAction = null;
        demoRunningAfterId = null;
        failing = false;
        Object.assign(ACTION_RUNNERS, realRunners);
        rows = [record("ライターAI", "記事を書く", "完了", "読み込み前の完了", "60")];
        agents = [];
        await loadCommandCenter();
        setVoiceState("idle");
        await wait(100);
        spoken.length = 0;
    };
    const check = (name, actual, expected) => {
        const ok = JSON.stringify(actual) === JSON.stringify(expected);
        results.push({ 試験: name, 判定: ok ? "合格" : "不合格", 実際: JSON.stringify(actual), 期待: JSON.stringify(expected) });
    };
    const run = async (name, body) => {
        await reset();
        try {
            await body();
        } catch (error) {
            results.push({ 試験: name, 判定: "不合格", 実際: String(error), 期待: "" });
        }
    };
    const WORKING = { hud: "working", hudLabel: "AI社員作業中", demo: "working", demoLabel: "AI社員作業中" };
    const LONG = { hud: "long-running", hudLabel: "長時間実行中・状態は要確認", demo: "long-running", demoLabel: "長時間実行中・状態は要確認" };
    const IDLE = { hud: "idle", hudLabel: "待機中", demo: "idle", demoLabel: "待機中" };

    await run("O1 通常の作業中は、作業中", async () => {
        const job = record("品質管理AI", "公開前の品質を採点する", "作業中");
        rows = [job, ...rows];
        agents = [agent("品質管理AI", "作業中")];
        await poll(1);
        check("O1 オーブ", orb(), WORKING);
    });

    await run("O2 長時間実行中だけなら、待機中でも作業中でもなく要確認", async () => {
        const job = record("ライターAI", "記事を書く", "長時間実行中");
        rows = [job, ...rows];
        agents = [agent("ライターAI", "長時間実行中")];
        await poll(2);
        check("O2 オーブ", orb(), LONG);
        const ring = getComputedStyle(document.querySelector("#hud-orb .hud-orb-ring"));
        check("O2 回転していない（動いているとは言わない）", ring.animationName, "none");
        check("O2 読み上げない", spoken, []);
    });

    await run("O3 作業中と長時間実行中が混在するなら、作業中に要確認を添える", async () => {
        rows = [record("品質管理AI", "採点", "作業中"), record("ライターAI", "記事を書く", "長時間実行中"), ...rows];
        agents = [agent("品質管理AI", "作業中"), agent("ライターAI", "長時間実行中")];
        await poll(1);
        check("O3 オーブ", orb(), { hud: "working", hudLabel: "AI社員作業中・長時間の作業は要確認",
            demo: "working", demoLabel: "AI社員作業中・長時間の作業は要確認" });
    });

    await run("O4 長時間実行中のあと完了したら、待機中に戻り、完了を1回だけ報告", async () => {
        const job = record("ライターAI", "記事を書く", "長時間実行中");
        rows = [job, ...rows];
        agents = [agent("ライターAI", "長時間実行中")];
        await poll(1);
        check("O4 完了前", orb().hud, "long-running");
        Object.assign(job, { status: "完了", detail: "記事の下書き", durationSeconds: "900" });
        agents = [agent("ライターAI", "待機中")];
        await poll(1);
        await wait(300);
        await poll(5);
        await wait(300);
        check("O4 オーブ", orb(), IDLE);
        check("O4 完了を1回", spoken, ["ライターAIが「記事を書く」を900秒で終えました。記事の下書き"]);
    });

    await run("O5 長時間実行中のあと失敗したら、要確認の表示を外し、失敗を1回だけ報告", async () => {
        const job = record("営業AI", "販売戦略を作る", "長時間実行中");
        rows = [job, ...rows];
        agents = [agent("営業AI", "長時間実行中")];
        await poll(1);
        Object.assign(job, { status: "エラー", detail: "WAI-API", durationSeconds: "720" });
        agents = [agent("営業AI", "エラー")];
        await poll(1);
        await wait(300);
        await poll(5);
        await wait(300);
        check("O5 オーブ", orb(), IDLE);
        check("O5 失敗を1回", spoken, ["営業AIの「販売戦略を作る」が失敗しました。WAI-API"]);
    });

    await run("O6 会話中・聞き取り中などの表示は、長時間実行中より優先", async () => {
        rows = [record("ライターAI", "記事を書く", "長時間実行中"), ...rows];
        agents = [agent("ライターAI", "長時間実行中")];
        await poll(1);
        const seen = {};
        for (const state of ["listening", "confirming", "thinking", "speaking", "waiting", "error"]) {
            setVoiceState(state);
            seen[state] = [orb().hud, orb().hudLabel, document.getElementById("voice-orb").dataset.voiceState];
        }
        check("O6 会話の状態を出す（HUD・マイク横とも）", seen, {
            listening: ["listening", "聞き取り中", "listening"], confirming: ["confirming", "確認中", "confirming"],
            thinking: ["thinking", "思考中", "thinking"], speaking: ["speaking", "読み上げ中", "speaking"],
            waiting: ["waiting", "次の発言を待っています", "waiting"], error: ["error", "エラー", "error"] });
        setVoiceState("idle");
        await wait(50);
        check("O6 待機に戻ると要確認に戻る", orb().hud, "long-running");
        check("O6 マイク横のオーブは会話専用のまま", document.getElementById("voice-orb").dataset.voiceState, "idle");
    });

    await run("O7 AI将軍だけが長時間実行中なら、社員の要確認にはしない", async () => {
        rows = [record("AI将軍", "相談に答える", "長時間実行中"), ...rows];
        agents = [agent("AI将軍", "長時間実行中")];
        await poll(1);
        check("O7 オーブ", orb(), IDLE);
    });

    await run("O8 AI社員の状態が取れないときは、要確認と言わない", async () => {
        agents = [agent("ライターAI", "長時間実行中")];
        await poll(1);
        agents = null;
        await poll(1);
        check("O8 オーブ", orb(), IDLE);
    });

    // 承認した操作の返答待ち（demoRunningAction）。画面の「実行する」の処理と同じく、承認した操作名を入れて試す。
    const demoAgentState = () => [...document.querySelectorAll("#demo-agent .demo-agent-row")]
        .map(row => `${row.querySelector("strong")?.textContent}:${row.dataset.agentStatus}`);
    // 「実行する」を押したときと同じく、操作名と、その時点で見えていた最新の記録の id を覚える。
    const approve = action => {
        demoRunningAction = action;
        demoRunningAfterId = latestActivityId;
    };

    await run("O9 承認直後で記録がまだないときは、作業中（今までどおり）", async () => {
        approve("runQuality");
        agents = [];
        await poll(1);
        check("O9 オーブ", orb(), WORKING);
        check("O9 デモの担当表示", demoAgentState(), ["品質管理AI:作業中"]);
    });

    await run("O10 承認した社員の記録が作業中なら、作業中", async () => {
        approve("runQuality");
        rows = [record("品質管理AI", "公開前の品質を採点する", "作業中"), ...rows];
        agents = [agent("品質管理AI", "作業中", "公開前の品質を採点する")];
        await poll(1);
        check("O10 オーブ", orb(), WORKING);
    });

    await run("O11 承認した社員の記録が長時間実行中になったら、返答待ちでも作業中とは言わない", async () => {
        approve("runQuality");
        rows = [record("品質管理AI", "公開前の品質を採点する", "長時間実行中"), ...rows];
        agents = [agent("品質管理AI", "長時間実行中", "公開前の品質を採点する")];
        await poll(1);
        check("O11 オーブ", orb(), LONG);
        check("O11 デモの担当表示も作業中と言わない", demoAgentState(), ["品質管理AI:長時間実行中"]);
    });

    await run("O12 承認した社員は長時間実行中で、別の社員が通常作業中なら、作業中＋要確認", async () => {
        approve("runQuality");
        rows = [record("営業AI", "販売戦略を作る", "作業中"), record("品質管理AI", "公開前の品質を採点する", "長時間実行中"), ...rows];
        agents = [agent("品質管理AI", "長時間実行中", "公開前の品質を採点する"), agent("営業AI", "作業中", "販売戦略を作る")];
        await poll(1);
        check("O12 オーブ", orb(), { hud: "working", hudLabel: "AI社員作業中・長時間の作業は要確認",
            demo: "working", demoLabel: "AI社員作業中・長時間の作業は要確認" });
        check("O12 デモの担当表示は通常作業中の社員", demoAgentState(), ["営業AI:作業中"]);
    });

    await run("O13 同じ社員の古い長時間記録があっても、新しく承認した操作の記録とはみなさない", async () => {
        // 承認より前からある、品質管理AIの古い記録が長時間実行中のまま残っている。
        rows = [record("品質管理AI", "公開前の品質を採点する", "長時間実行中"), ...rows];
        agents = [agent("品質管理AI", "長時間実行中", "公開前の品質を採点する")];
        await poll(1);
        check("O13 承認前（古い記録だけ）", orb(), LONG);
        // 新しい品質管理AIの操作を承認。新しい記録はまだ取れていない。
        approve("runQuality");
        await poll(1);
        check("O13 承認直後：応答待ち（作業中）と、古い記録の要確認を分けて出す", orb(), { hud: "working",
            hudLabel: "AI社員作業中・長時間の作業は要確認", demo: "working", demoLabel: "AI社員作業中・長時間の作業は要確認" });
        check("O13 承認直後のデモの担当表示は、今回の操作", demoAgentState(), ["品質管理AI:作業中"]);
        // 新しい記録ができて作業中になった（社員の最新の記録は新しいほう）。
        const current = record("品質管理AI", "公開前の品質を採点する", "作業中");
        rows = [current, ...rows];
        agents = [agent("品質管理AI", "作業中", "公開前の品質を採点する")];
        await poll(1);
        check("O13 新しい記録が作業中", orb(), WORKING);
        // 今回の操作そのものが長時間実行中になった。
        current.status = "長時間実行中";
        agents = [agent("品質管理AI", "長時間実行中", "公開前の品質を採点する")];
        await poll(1);
        check("O13 今回の操作が長時間実行中", orb(), LONG);
    });

    await run("O14 承認の返答待ちと長時間実行中があっても、会話の状態を優先", async () => {
        rows = [record("ライターAI", "記事を書く", "長時間実行中"), ...rows];
        agents = [agent("ライターAI", "長時間実行中")];
        await poll(1);
        approve("runQuality");
        await poll(1);
        const seen = {};
        for (const state of ["listening", "thinking", "speaking"]) {
            setVoiceState(state);
            seen[state] = [orb().hud, document.getElementById("voice-orb").dataset.voiceState];
        }
        check("O14 会話の状態を出す", seen, { listening: ["listening", "listening"], thinking: ["thinking", "thinking"],
            speaking: ["speaking", "speaking"] });
        setVoiceState("idle");
        await wait(50);
        check("O14 待機に戻ると、作業中＋要確認", orb().hudLabel, "AI社員作業中・長時間の作業は要確認");
    });

    // 実行ログの取得に失敗したとき（v4）。今回の操作の記録を特定できない間は、作業中とも長時間実行中とも言わず、
    // 「応答待ち・実行状態は未確認」として分けて出す。ここからは画面の「実行する」ボタンを実際に押す。
    // 操作の本体（ACTION_RUNNERS）だけを、終わるタイミングをこちらで決められる偽物に差し替える。
    const AWAIT = { hud: "awaiting", hudLabel: "操作の応答待ち・実行状態は未確認",
        demo: "awaiting", demoLabel: "操作の応答待ち・実行状態は未確認" };
    const AWAIT_LONG = { hud: "awaiting", hudLabel: "操作の応答待ち・長時間の作業は要確認",
        demo: "awaiting", demoLabel: "操作の応答待ち・長時間の作業は要確認" };
    const clickApprove = action => {
        let finish;
        ACTION_RUNNERS[action] = () => new Promise(resolve => { finish = resolve; });
        const box = document.createElement("div");
        renderActionApproval(box, { action });
        box.querySelector(".action-approval-buttons button:not(.secondary-button)").click();
        return async () => {
            finish("完了しました。");
            await wait(50);
        };
    };

    await run("O15 取得成功→取得失敗→承認→復旧時に承認前の未取得記録が返っても、今回の操作の記録とみなさない", async () => {
        await poll(1);
        check("O15 取得成功で基準がある", latestActivityId !== null, true);
        failing = true;
        // 取得に失敗している間に、承認より前の品質管理AIの記録ができた（画面にはまだ届いていない）。
        const unseen = record("品質管理AI", "公開前の品質を採点する", "長時間実行中");
        await poll(1);
        check("O15 取得失敗で基準を無効にする", latestActivityId, null);
        const finish = clickApprove("runQuality");
        check("O15 承認時の基準がない", demoRunningAfterId, null);
        check("O15 承認直後：作業中と言わず応答待ち", orb(), AWAIT);
        check("O15 承認直後のデモの担当表示", demoAgentState(), ["品質管理AI:応答待ち"]);
        // 復旧。承認前にできていた記録（id は古い基準より大きい）が長時間実行中で返る。
        failing = false;
        rows = [unseen, ...rows];
        agents = [agent("品質管理AI", "長時間実行中", "公開前の品質を採点する")];
        await poll(1);
        check("O15 復旧後も推定で紐付けない（応答待ち＋別の要確認）", orb(), AWAIT_LONG);
        check("O15 復旧後のデモの担当表示も応答待ち", demoAgentState(), ["品質管理AI:応答待ち"]);
        // 復旧後に新しい記録が増えても、基準がない操作には紐付けない。
        rows = [record("品質管理AI", "公開前の品質を採点する", "長時間実行中"), ...rows];
        await poll(1);
        check("O15 復旧後に記録が増えても紐付けない", orb(), AWAIT_LONG);
        // 操作の返答が来たら応答待ちを外す。残っている長時間実行中だけの要確認に戻る。
        await finish();
        await poll(1);
        check("O15 操作の返答後", orb(), LONG);
        check("O15 応答待ちが残らない", demoRunningAction, null);
    });

    await run("O16 基準のある承認のあと司令本部の取得に失敗したら、作業中と言わず応答待ち", async () => {
        await poll(1);
        const finish = clickApprove("runQuality");
        check("O16 承認時の基準がある", demoRunningAfterId !== null, true);
        check("O16 承認直後は作業中", orb(), WORKING);
        failing = true;
        await poll(1);
        check("O16 取得失敗中", orb(), AWAIT);
        check("O16 取得失敗中のデモの担当表示", demoAgentState(), ["品質管理AI:応答待ち"]);
        // 復旧。承認より後にできた今回の操作の記録が作業中で返る。
        failing = false;
        rows = [record("品質管理AI", "公開前の品質を採点する", "作業中"), ...rows];
        agents = [agent("品質管理AI", "作業中", "公開前の品質を採点する")];
        await poll(1);
        check("O16 復旧後は作業中", orb(), WORKING);
        await finish();
        agents = [agent("品質管理AI", "待機中")];
        await poll(1);
        check("O16 操作の返答後", orb(), IDLE);
    });

    await run("O17 基準のある承認のあと実行ログだけ取れないときも、応答待ち", async () => {
        await poll(1);
        const finish = clickApprove("runQuality");
        const keep = rows;
        rows = null;
        await poll(1);
        check("O17 実行ログが取れない間", orb(), AWAIT);
        check("O17 実行ログが取れない間のデモの担当表示", demoAgentState(), ["品質管理AI:応答待ち"]);
        rows = keep;
        await poll(1);
        check("O17 実行ログが戻ったら作業中（承認後の記録はまだない）", orb(), WORKING);
        await finish();
        await poll(1);
        check("O17 操作の返答後", orb(), IDLE);
    });

    demoRunningAction = null;
    demoRunningAfterId = null;
    failing = false;
    Object.assign(ACTION_RUNNERS, realRunners);
    agents = [];
    await loadCommandCenter();
    console.table(results);
    const failed = results.filter(result => result.判定 !== "合格").length;
    console.log(failed === 0 ? "すべて合格" : `${failed}件が不合格`);
    window.__orbRegression = { results, spoken };
    return results;
})();
