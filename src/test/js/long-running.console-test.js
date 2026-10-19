// AI社員の作業が長引いたときに、失敗と誤って報告しないことの回帰テスト（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 約30秒で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、読み上げと司令本部の巡回（/api/command-center）を、このページの中だけで偽物に差し替える。
// 巡回の返答は、サーバーが画面向けに変換したあとの状態（作業中・長時間実行中・完了・エラー）を返す。
// サーバーでの変換（10分を超えた作業中を「長時間実行中」にする）は、Java のテスト AiActivityStatusTest で確かめる。
// OpenAI は呼ばれず、DB にも書き込まない。読み上げは偽物なので、実際に聞こえるかは別に確かめること。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const results = [];
    const spoken = [];

    // 読み上げ：少しして始まり、すぐ終わったことにする。読み上げた文を記録する。
    window.speechSynthesis.speak = utterance => {
        setTimeout(() => {
            spoken.push(utterance.text);
            utterance.onstart?.({});
            setTimeout(() => utterance.onend?.({}), 50);
        }, 20);
    };
    window.speechSynthesis.cancel = () => {};

    // 司令本部の巡回：rows（新しい順）を実行ログとして、agents を AI社員の状態として返す。
    let rows = [];
    let agents = [];
    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
        const path = new URL(typeof input === "string" ? input : input.url, location.href).pathname;
        if (path === "/api/command-center") {
            return new Response(JSON.stringify({ numbers: null, money: null, priorityTasks: null,
                agents, activities: rows.slice(0, 20) }), { headers: { "Content-Type": "application/json" } });
        }
        return realFetch(input, init);
    };

    let nextId = 800000;
    // 同じ記録（id）の状態を変えて、巡回で受け取る。
    const record = (agent, action, status, detail = "", seconds = "") =>
        ({ id: String(nextId++), agent, action, status, detail, startedAt: "2026-10-16 10:00:00.0", durationSeconds: seconds });
    const setState = async (activity, status, detail = activity.detail, seconds = activity.durationSeconds) => {
        Object.assign(activity, { status, detail, durationSeconds: seconds });
        agents = [{ agent: activity.agent, lastAction: activity.action, status: status === "完了" ? "待機中" : status }];
        await loadCommandCenter();
    };
    const poll = async times => { for (let i = 0; i < times; i++) await loadCommandCenter(); };
    const logRow = () => document.querySelector("#hud-log .hud-log-row");
    const agentRow = name => [...document.querySelectorAll("#hud-agents .hud-agent")]
        .find(row => row.querySelector("strong")?.textContent === name);

    const reset = async () => {
        if (typeof pendingReports !== "undefined") {
            pendingReports = [];
            reportRetryAt = 0;
            reportSpeaking = false;
            reportedActivityIds = null;
            if (typeof reportStartFailures !== "undefined") reportStartFailures = 0;
        }
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

    await run("L1 通常の作業中から完了は、1回だけ報告する", async () => {
        const job = record("品質管理AI", "公開前の品質を採点する", "作業中");
        rows = [job, ...rows];
        await setState(job, "作業中");
        await poll(2);
        check("L1 作業中は報告しない", spoken, []);
        await setState(job, "完了", "72点", "13");
        await wait(300);
        await poll(5);
        await wait(300);
        check("L1 完了を1回", spoken, ["品質管理AIが「公開前の品質を採点する」を13秒で終えました。72点"]);
    });

    await run("L2 長時間実行中は失敗と言わず、その後の完了を1回だけ報告する", async () => {
        const job = record("ライターAI", "記事を書く", "作業中");
        rows = [job, ...rows];
        await setState(job, "作業中");
        await setState(job, "長時間実行中");
        await poll(5);
        await wait(300);
        check("L2 長時間実行中は読み上げない", spoken, []);
        check("L2 実行ログに長時間実行中と出る", [logRow()?.dataset.agentStatus, logRow()?.querySelector(".hud-agent-state")?.textContent],
            ["長時間実行中", "長時間実行中"]);
        check("L2 AI社員の一覧にも長時間実行中と出る", agentRow("ライターAI")?.querySelector(".hud-agent-state")?.textContent, "長時間実行中");
        check("L2 色は失敗の赤ではない", getComputedStyle(logRow().querySelector(".hud-agent-state")).color !== "rgb(255, 123, 123)", true);
        await setState(job, "完了", "記事の下書き", "900");
        await wait(300);
        await poll(5);
        await wait(300);
        check("L2 その後の完了を1回", spoken, ["ライターAIが「記事を書く」を900秒で終えました。記事の下書き"]);
    });

    await run("L3 長時間実行中のあとの本当の失敗は、1回だけ失敗と報告する", async () => {
        const job = record("営業AI", "販売戦略を作る", "作業中");
        rows = [job, ...rows];
        await setState(job, "長時間実行中");
        await poll(3);
        check("L3 長時間実行中は読み上げない", spoken, []);
        await setState(job, "エラー", "WAI-API", "720");
        await wait(300);
        await poll(5);
        await wait(300);
        check("L3 失敗を1回", spoken, ["営業AIの「販売戦略を作る」が失敗しました。WAI-API"]);
        check("L3 実行ログはエラー", logRow()?.dataset.agentStatus, "エラー");
    });

    await run("L4 通常の失敗は、すぐに1回だけ報告する", async () => {
        const job = record("軍師AI", "需要と切り口を分析する", "作業中");
        rows = [job, ...rows];
        await setState(job, "作業中");
        await setState(job, "エラー", "WAI-FORMAT", "8");
        await wait(300);
        await poll(5);
        await wait(300);
        check("L4 失敗を1回", spoken, ["軍師AIの「需要と切り口を分析する」が失敗しました。WAI-FORMAT"]);
    });

    console.table(results);
    const failed = results.filter(result => result.判定 !== "合格").length;
    console.log(failed === 0 ? "すべて合格" : `${failed}件が不合格`);
    window.__longRunningRegression = { results, spoken };
    return results;
})();
