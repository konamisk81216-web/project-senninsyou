// 会話画面（talk-visual.js）の回帰テスト（ブラウザのコンソールで手動実行する）。
//
// 実行方法：
//   1. ローカルでアプリを起動し、Chrome で http://localhost:8080 を開く（本番では実行しない）。
//   2. 開発者ツールのコンソールに、このファイルの中身をすべて貼り付けて実行する。
//   3. 約1分で、試験ごとの「合格／不合格」が表で出る。終わったらページを再読み込みする。
//
// このテストは、読み上げ・司令本部の巡回（/api/command-center）・AI将軍への送信（/api/ai/command）・
// マイク（getUserMedia）を、このページの中だけで偽物に差し替える。マイクの偽物は、作った音を流す音声の流れ。
// OpenAI は呼ばれず、DB にも書き込まない。本物のマイクは使わない（実マイクの確認は本人が行う）。
(async () => {
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const results = [];
    const check = (name, ok, detail = "") => results.push({ 試験: name, 判定: ok ? "合格" : "不合格", 詳細: typeof detail === "string" ? detail : JSON.stringify(detail) });
    const T = () => window.__talk;
    const text = id => document.getElementById(id).textContent;

    // 読み上げの偽物（音は出さない）。
    window.speechSynthesis.speak = utterance => { setTimeout(() => { utterance.onstart?.({}); setTimeout(() => utterance.onend?.({}), 1500); }, 20); };
    window.speechSynthesis.cancel = () => {};

    // 司令本部とAI将軍への送信の偽物。
    let agents = [
        { agent: "AI将軍", lastAction: "相談に答える", status: "待機中" },
        { agent: "ライターAI", lastAction: "記事の下書きを書く", status: "長時間実行中" },
        { agent: "品質管理AI", lastAction: "公開前の品質を採点する", status: "作業中" }
    ];
    let activities = [{ id: "990001", agent: "品質管理AI", action: "公開前の品質を採点する", status: "作業中", detail: "", startedAt: "2026-10-20 10:00:00.0", durationSeconds: "" }];
    let commandPosts = 0;
    const realFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
        const path = new URL(typeof input === "string" ? input : input.url, location.href).pathname;
        if (path === "/api/command-center") {
            return new Response(JSON.stringify({ numbers: null, money: null, priorityTasks: null, agents, activities }), { headers: { "Content-Type": "application/json" } });
        }
        if (path === "/api/ai/command") {
            commandPosts++;
            await wait(600);
            return new Response(JSON.stringify({
                summary: "偽の返答です。品質管理AIで採点できます。", revenueInsight: "", performanceDecision: "", marketOpportunity: "",
                nextTask: "", priority: "", assignedAgent: "", action: "runQuality", actionLabel: "採点しますか？", actionTheme: "", actionAudience: ""
            }), { headers: { "Content-Type": "application/json" } });
        }
        return realFetch(input, init);
    };

    // マイクの偽物：作った音を MediaStream にして返す。kind で音を切り替える。
    const gen = new (window.AudioContext || window.webkitAudioContext)();
    let genNodes = [], genTimer = null, denyMic = false, micRequests = 0;
    const noise = amplitude => {
        const buffer = gen.createBuffer(1, gen.sampleRate * 2, gen.sampleRate);
        const data = buffer.getChannelData(0);
        for (let i = 0; i < data.length; i++) data[i] = (Math.random() * 2 - 1) * amplitude;
        const node = gen.createBufferSource(); node.buffer = buffer; node.loop = true; return node;
    };
    let dest = gen.createMediaStreamDestination();
    function playKind(kind) {
        clearInterval(genTimer); genTimer = null;
        genNodes.forEach(node => { try { node.stop?.(); node.disconnect(); } catch (e) {} });
        genNodes = [];
        if (kind === "suppressed") {
            // 雑音除去で静かな部屋：とても小さな音（約-78dB）に、ときどき部屋の小さな音（-54〜-48dB）。
            const base = noise(0.00022); base.connect(dest); base.start();
            const pops = noise(1), gp = gen.createGain(); gp.gain.value = 0; pops.connect(gp); gp.connect(dest); pops.start();
            genNodes.push(base, pops, gp);
            genTimer = setInterval(() => {
                const t = gen.currentTime, amp = Math.pow(10, (-54 + Math.random() * 6) / 20) * Math.sqrt(3);
                gp.gain.setTargetAtTime(amp, t, 0.02); gp.gain.setTargetAtTime(0, t + 0.25, 0.03);
            }, 900);
            return;
        }
        const ambient = noise(0.0043); ambient.connect(dest); ambient.start();   // 部屋の音 約-52dB
        genNodes.push(ambient);
        if (kind === "silence") return;
        // 大きい声は、はっきり大きい声（最大 0dB 付近）。散る仕組みが動くかの確認用。
        // どの大きさから散るのが自然かは、本人が実マイク（感度「低め」）で確かめて採用している（試作の確認記録）。
        const peak = { normal: 0.4, loud: 5.0 }[kind];
        const osc = gen.createOscillator(); osc.type = "sawtooth";
        const formant = gen.createBiquadFilter(); formant.type = "bandpass"; formant.frequency.value = 900; formant.Q.value = 1.2;
        const gv = gen.createGain(); gv.gain.value = 0;
        osc.connect(formant).connect(gv); gv.connect(dest); osc.start();
        genNodes.push(osc, formant, gv);
        genTimer = setInterval(() => {
            const t = gen.currentTime;
            osc.frequency.setTargetAtTime(110 + Math.random() * 80, t, 0.05);
            formant.frequency.setTargetAtTime(500 + Math.random() * 1500, t, 0.04);
            gv.gain.setTargetAtTime(peak * (0.6 + Math.random() * 0.4), t, 0.02);
            gv.gain.setTargetAtTime(0, t + 0.12 + Math.random() * 0.1, 0.05);
        }, 230);
    }
    const realGetUserMedia = navigator.mediaDevices?.getUserMedia?.bind(navigator.mediaDevices);
    // micDelay：許可が出るまでの待ち時間（許可待ちの再現）。twoTracks：トラックを2本持つ入力を返す（全トラックが止まるかを見る）。
    // acquired：これまでに返した入力すべて。最後に、全トラックが止まったかを確かめる。
    let micDelay = 0, twoTracks = false;
    const acquired = [];
    navigator.mediaDevices.getUserMedia = async () => {
        micRequests++;
        if (micDelay) await wait(micDelay);
        if (denyMic) { const error = new Error("denied"); error.name = "NotAllowedError"; throw error; }
        dest = gen.createMediaStreamDestination();
        playKind(currentKind);
        const stream = twoTracks
            ? new MediaStream([...dest.stream.getAudioTracks(), ...gen.createMediaStreamDestination().stream.getAudioTracks()])
            : dest.stream;
        acquired.push(stream);
        return stream;
    };
    const allEnded = stream => stream.getTracks().length > 0 && stream.getTracks().every(track => track.readyState === "ended");
    const trackStates = stream => stream.getTracks().map(track => track.readyState);
    let currentKind = "silence";
    const useKind = kind => { currentKind = kind; playKind(kind); };
    const avg = async (ms, read) => { let s = 0, n = 0; const end = Date.now() + ms; while (Date.now() < end) { s += read(); n++; await wait(40); } return s / n; };
    const maxOf = async (ms, read) => { let m = 0; const end = Date.now() + ms; while (Date.now() < end) { m = Math.max(m, read()); await wait(40); } return m; };

    // アプリの状態（会話の処理側）の写し。見た目用のマイクで変わらないことを見る。
    const appState = () => ({
        voiceSessionActive, voiceListening, commandInFlight, currentVoiceState,
        canSpeakReports: canSpeakReports(), micIndicatorHidden: document.getElementById("mic-indicator").hidden,
        demoMicHidden: document.getElementById("demo-mic").hidden
    });

    try {
        await loadCommandCenter();
        showPage("talk");
        await wait(400);
        const canvas = document.getElementById("talk-orb");
        check("V1 「会話」画面が開き、発光体を描き始める", T().running && canvas.width > 0 && canvas.height > 0 && !document.querySelector('[data-page-panel="talk"]').hidden,
            { running: T().running, size: [canvas.width, canvas.height] });

        // V2：会話の状態を読むだけで表示を切り替える（マイクはオフ）。
        const states = {};
        for (const state of ["listening", "thinking", "speaking", "idle"]) {
            setVoiceState(state);
            await wait(state === "idle" ? 1500 : 700);
            states[state] = [text("talk-state"), text("talk-source"), Number(T().view.energy.toFixed(2))];
        }
        check("V2 会話の状態を表示：聞き取り中（マイクオフなので反応の元なし）・考え中と読み上げ中は演出・待機で落ち着く",
            states.listening[0] === "聞き取り中" && states.listening[1] === "反応の元：なし"
            && states.thinking[0] === "思考中" && states.thinking[1] === "反応の元：演出（実測ではない）"
            && states.speaking[0] === "読み上げ中" && states.speaking[1] === "反応の元：演出（実測ではない）" && states.speaking[2] > 0.2
            && states.idle[0] === "待機中" && states.idle[2] < 0.05, states);

        // V3：字幕はデモ表示の字幕を写す。
        setDemoSubtitle("テストの指示です", "テストの返答です");
        await wait(50);
        check("V3 字幕：あなた・AI将軍の文を写す", text("talk-sub-user") === "テストの指示です" && text("talk-sub-ai") === "テストの返答です",
            [text("talk-sub-user"), text("talk-sub-ai")]);

        // V4：AI社員の状態と実行ログ。
        const agentRows = [...document.querySelectorAll("#talk-agents .talk-agent")].map(row => [row.querySelector("strong").textContent, row.dataset.status]);
        const longLines = [...document.querySelectorAll('#talk-agents .talk-agent[data-status="長時間実行中"] .talk-tag-line')].map(line => line.textContent);
        check("V4 AI社員の状態（サーバーの状態をそのまま。長時間実行中は「状態は要確認」と2行）",
            JSON.stringify(agentRows) === JSON.stringify([["AI将軍", "待機中"], ["ライターAI", "長時間実行中"], ["品質管理AI", "作業中"]])
            && JSON.stringify(longLines) === JSON.stringify(["長時間実行中", "状態は要確認"])
            && document.querySelectorAll("#talk-log .talk-log-row").length === 1, { agentRows, longLines });
        check("V4b AI社員の作業の印は、ダッシュボードのオーブと同じ文言", text("talk-work") === "AI社員作業中・長時間の作業は要確認", text("talk-work"));
        const keepAgents = agents, keepActivities = activities;
        agents = null; activities = null;
        await loadCommandCenter();
        check("V4c 取れないときは「取得できません」", text("talk-agents") === "取得できません" && text("talk-log") === "取得できません", [text("talk-agents"), text("talk-log")]);
        agents = keepAgents; activities = keepActivities;
        await loadCommandCenter();

        // V5：偽のAI返答で、送信・承認の流れが今までどおりで、会話画面は表示だけを写す。
        document.getElementById("command-input").value = "採点して";
        let sawThinking = false;
        const watch = setInterval(() => { if (text("talk-state") === "思考中") sawThinking = true; }, 50);
        document.getElementById("command-button").click();
        await wait(1500);
        clearInterval(watch);
        const approvalCards = document.querySelectorAll("#ai-response .action-approval").length;
        check("V5 偽のAI返答：送信は1回、返答中は会話画面も「思考中」、字幕にAI将軍の返答、承認カードは今までどおりダッシュボード側に1つ",
            commandPosts === 1 && sawThinking && text("talk-sub-ai") === "偽の返答です。品質管理AIで採点できます。" && approvalCards === 1
            && document.querySelectorAll('[data-page-panel="talk"] .action-approval').length === 0,
            { commandPosts, sawThinking, ai: text("talk-sub-ai"), approvalCards });
        setVoiceState("idle");
        await wait(300);

        // V6：見た目用のマイクを入れても、会話の処理側の状態は変わらない。
        useKind("silence");
        const before = appState();
        document.getElementById("talk-mic-toggle").click();
        await wait(1500);
        const during = appState();
        check("V6 見た目用のマイクを入れても、聞き取り・送信・報告の状態は変わらない",
            T().micActive && JSON.stringify(before) === JSON.stringify(during), { before, during, micActive: T().micActive });
        check("V6b 見た目用のマイクがオンの間は、反応の元が「マイクの実測音量」", text("talk-source") === "反応の元：マイクの実測音量", text("talk-source"));

        // V7：音量による反応（感度は採用した「低め」）。
        await wait(1500);
        const silence = { energy: await avg(2000, () => T().view.energy), gate: T().level.gate };
        check("V7 黙っている（部屋の音だけ）：反応しない", silence.energy < 0.02 && !silence.gate && T().sensitivity === "low", silence);
        useKind("normal"); await wait(1500);
        const normal = { energy: await avg(2500, () => T().view.energy), scatter: await maxOf(1500, () => T().view.scatter), dbMax: await maxOf(1500, () => T().level.db + 100) - 100 };
        check("V7b 普通の声：反応するが散らない", normal.energy > 0.08 && normal.scatter < 0.05, normal);
        useKind("loud"); await wait(1500);
        const loud = { energy: await avg(2500, () => T().view.energy), scatter: await maxOf(2500, () => T().view.scatter), dbMax: await maxOf(1500, () => T().level.db + 100) - 100 };
        check("V7c 大きい声：強く反応し、中心のオーブが散る", loud.energy > normal.energy * 1.5 && loud.scatter > 0.2, loud);
        useKind("silence");
        const t0 = Date.now(); let settled = null;
        while (Date.now() - t0 < 3000) { if (T().view.energy < 0.05) { settled = Date.now() - t0; break; } await wait(30); }
        const v7d = { mode: T().view.mode, source: T().view.source, gate: T().level.gate, raw: Number(T().level.raw.toFixed(3)), db: Number(T().level.db.toFixed(1)), base: Number(T().level.base.toFixed(1)), energy: Number(T().view.energy.toFixed(3)), voiceState: currentVoiceState };
        check("V7d 話し終わると自然に落ち着く（3秒以内）", settled !== null, `落ち着くまで=${settled}ms ${JSON.stringify(v7d)}`);
        useKind("suppressed"); await wait(2500);
        const room = { energy: await avg(4000, () => T().view.energy), gateOn: await avg(1000, () => T().level.gate ? 1 : 0) };
        check("V7e 雑音除去で静かな部屋の小さな音（テレビ・周りの人の声に近い大きさ）：反応しない", room.energy < 0.03 && room.gateOn < 0.1, room);

        // V8：読み上げ中は、マイクがオンでも演出（スピーカーの声を実測に使わない）。
        useKind("loud"); await wait(800);
        setVoiceState("speaking");
        await wait(400);
        check("V8 読み上げ中はマイクがオンでも演出", text("talk-source") === "反応の元：演出（実測ではない）" && T().view.source === "show", text("talk-source"));
        setVoiceState("idle");

        // V9：会話画面を離れると、見た目用のマイクを止める。
        const tracksBefore = T().micTracks;
        showPage("command");
        await wait(300);
        check("V9 会話画面を離れると見た目用のマイクを止め、描くのも止める", !T().micActive && !T().running && tracksBefore.includes("live"),
            { tracksBefore, micActive: T().micActive, running: T().running });
        showPage("talk");
        await wait(300);
        check("V9b 戻っても見た目用のマイクは自動でオンにならない", !T().micActive && T().running && text("talk-mic-toggle") === "🎚 声に反応させる", text("talk-mic-toggle"));

        // V10：許可されないとき。
        denyMic = true;
        const beforeDeny = appState();
        document.getElementById("talk-mic-toggle").click();
        await wait(300);
        check("V10 マイクが許可されないとき：説明を出し、会話の処理側は変わらない",
            !T().micActive && text("talk-notice").includes("許可されませんでした") && JSON.stringify(beforeDeny) === JSON.stringify(appState()), text("talk-notice"));
        denyMic = false;

        // V11：会話画面の「音声会話を開始」は、既存のボタンを押すのと同じ（処理は app.js のまま）。
        // 返答待ちの間は会話を始めない、という既存の決まりで、実際には会話を始めずに確かめる。
        const sessionButton = document.getElementById("voice-session-toggle");
        let delegated = 0;
        const spy = () => { delegated++; };
        sessionButton.addEventListener("click", spy);
        commandInFlight = true;
        document.getElementById("talk-session-toggle").click();
        await wait(50);
        commandInFlight = false;
        sessionButton.removeEventListener("click", spy);
        check("V11 会話画面のボタンは既存のボタンを押す。既存の決まり（返答待ちの間は始めない）もそのまま",
            delegated === 1 && !voiceSessionActive && text("talk-voice-status") === "AI将軍の返答を待っています。返答のあとに会話を始めてください。"
            && text("talk-session-toggle") === sessionButton.textContent, { delegated, status: text("talk-voice-status") });

        // ===== マイクの開始待ちと停止（許可待ちを再現する） =====
        const micButton = document.getElementById("talk-mic-toggle");
        twoTracks = true;
        useKind("silence");

        // V12：許可待ち中の連打で、開始要求が重ならない。
        micDelay = 800;
        let requests0 = micRequests;
        for (let i = 0; i < 5; i++) micButton.click();
        await wait(100);
        const pendingView = { requests: micRequests - requests0, pending: T().micPending, active: T().micActive, button: micButton.textContent };
        await wait(1100);
        const s12 = acquired.at(-1);
        const afterGrant = { active: T().micActive, tracks: trackStates(s12) };
        micButton.click();   // 止める
        await wait(100);
        check("V12 許可待ち中の連打：開始要求は1回だけ、許可待ちのまま（取り消しにならない）、許可後に使用中、止めると全トラックが止まる",
            pendingView.requests === 1 && pendingView.pending && !pendingView.active && pendingView.button.startsWith("許可を待っています")
            && afterGrant.active && afterGrant.tracks.every(state => state === "live") && micRequests - requests0 === 1
            && allEnded(s12) && !T().micActive, { pendingView, afterGrant, afterStop: trackStates(s12), requests: micRequests - requests0 });

        // V13：許可待ち中に画面を移り、戻ってくる。
        requests0 = micRequests;
        let late0 = T().lateStreamsStopped;
        micButton.click();
        await wait(100);
        showPage("command");
        await wait(50);
        const leftWhilePending = { pending: T().micPending, active: T().micActive };
        await wait(1100);   // 許可が遅れて返る
        const s13 = acquired.at(-1);
        const afterLate13 = { active: T().micActive, pending: T().micPending, tracks: trackStates(s13), late: T().lateStreamsStopped - late0 };
        showPage("talk");
        await wait(400);
        check("V13 許可待ち中に画面を移る：要求を無効にし、遅れて返った入力は全トラックをすぐ止める。戻っても自動で再開しない",
            !leftWhilePending.pending && !leftWhilePending.active && micRequests - requests0 === 1
            && !afterLate13.active && !afterLate13.pending && allEnded(s13) && afterLate13.late === 1
            && !T().micActive && !T().micPending && micRequests - requests0 === 1 && micButton.textContent === "🎚 声に反応させる",
            { leftWhilePending, afterLate13, afterReturn: { active: T().micActive, pending: T().micPending, requests: micRequests - requests0, button: micButton.textContent } });

        // V14：許可待ちを止めたあと、遅れて許可が返る。
        micDelay = 1500;
        requests0 = micRequests;
        late0 = T().lateStreamsStopped;
        micButton.click();
        await wait(700);
        micButton.click();   // 0.5秒より後なので取り消し
        await wait(50);
        const cancelled = { pending: T().micPending, active: T().micActive, notice: text("talk-notice"), button: micButton.textContent };
        await wait(1200);
        const s14 = acquired.at(-1);
        const afterLate14 = { active: T().micActive, pending: T().micPending, tracks: trackStates(s14), late: T().lateStreamsStopped - late0 };
        await wait(500);
        check("V14 停止後の遅い許可：取り消しのあとに返った入力は使わず、全トラックをすぐ止める。自動で再開しない",
            !cancelled.pending && !cancelled.active && cancelled.notice === "マイクの許可待ちを取り消しました。" && cancelled.button === "🎚 声に反応させる"
            && micRequests - requests0 === 1 && !afterLate14.active && allEnded(s14) && afterLate14.late === 1
            && !T().micActive && !T().micPending, { cancelled, afterLate14 });

        // V15：これまでに返したマイク入力は、すべて全トラックが止まっている（使用中のものを止めたあと）。
        T().stopVisualMic();
        await wait(100);
        check("V15 これまでに取得したマイク入力すべてで、全トラックが止まっている",
            acquired.length > 0 && acquired.every(allEnded), { count: acquired.length, states: acquired.map(trackStates) });
        micDelay = 0; twoTracks = false;
    } catch (error) {
        check("例外", false, String(error && error.stack || error));
    }

    T()?.stopVisualMic();
    clearInterval(genTimer);
    if (realGetUserMedia) navigator.mediaDevices.getUserMedia = realGetUserMedia;
    console.table(results);
    const failed = results.filter(result => result.判定 !== "合格").length;
    console.log(failed === 0 ? "すべて合格" : `${failed}件が不合格`);
    window.__talkRegression = { results, micRequests };
    return results;
})();
