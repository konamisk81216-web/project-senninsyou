// 会話画面（サイドメニューの「会話」）。中央の発光体・字幕・AI社員の状態・直近の実行ログを1画面にまとめる。
// 見た目と反応は、本人が実マイクで確かめて採用した試作（4版目、感度「低め」）をそのまま使う。
//
// この画面は表示だけを受け持つ。音声認識・送信・承認・報告の判断には関わらない。
// ・会話の状態（聞き取り中・考え中・読み上げ中など）は app.js の currentVoiceState を読むだけ。書き換えない。
// ・字幕は、app.js がデモ表示の字幕（#demo-user-line・#demo-ai-line）に書いた文字と話者を写すだけ。
// ・AI社員の状態と実行ログは、司令本部の巡回（5秒ごと）で取れたデータを renderTalkPanels で受け取って描くだけ。
// ・「声に反応させる」は、見た目のためだけに別のマイク入力を開く。音は端末の中で音量の数値にするだけで、
//   録音・保存・送信はしない。音声認識（ブラウザによっては音声が認識サービスへ送られる）とは別の仕組みで、
//   この入力の音量は、聞き取り・送信・承認・報告のどの判断にも使わない。
// ・AIの読み上げ中と考え中の動きは演出（実測ではない）。ブラウザの読み上げ音は分析に回せないため。
(() => {
    const panel = document.querySelector('[data-page-panel="talk"]');
    if (!panel) return;
    const $ = id => document.getElementById(id);
    const canvas = $("talk-orb");
    const g = canvas.getContext("2d");
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

    // ===== 音の実測（試作4版目と同じ決まり） =====
    // 周囲の音の大きさを見積もり、それより十分大きい音だけを声として扱う。
    // ・開始から0.8秒は周囲の音を測るだけで、反応しない。
    // ・周囲の音の見積もり：「静か」と判定していた直近約2.5秒の真ん中の値。話している間も、見積もりに近い音（＋8dB未満）は足す。
    // ・話し始め：少しならした音量（約0.08秒）が判定の基準より10dB以上大きい状態が3コマ続いたら「話している」。
    // ・話し終わり：もっとゆっくりならした音量（約0.3秒）が「判定の基準＋6dB」を下回る状態が0.35秒続いたら「静か」。
    // ・判定の基準は周囲の音の見積もり。ただし「感度」の最低音量−10dBより下げない（ブラウザの雑音除去で見積もりが下がりすぎても、
    //   テレビや周りの人の声のような小さな音で反応しないように）。
    const GATE = { openDb: 10, closeDb: 6, openFrames: 3, holdMs: 350, calibrateMs: 800, floorMin: -75, floorMax: -30,
        historyFrames: 150, quietPercentile: 0.5, smoothTau: 0.08, closeTau: 0.3 };
    const RANGE_DB = 40, CURVE = 1.3;
    const SENSITIVITY = { low: { range: 1.3, minDb: -42 }, std: { range: 1, minDb: -46 }, high: { range: 0.8, minDb: -50 } };
    let sens = SENSITIVITY.low;           // 本人が採用した設定（低め）を初期値にする

    const audio = { ctx: null, analyser: null, stream: null, source: null, time: null, freq: null };
    const level = { raw: 0, db: -100, smooth: 0, floor: -60, base: -52, gate: false, calibrating: false, toggles: 0, dbSmooth: -100, dbSlow: -100 };
    let gateAboveFrames = 0, gateQuietSince = 0, calibrateUntil = 0, lastMeasureAt = 0, framesSinceFloor = 0;
    const makeHistory = () => ({ values: new Float32Array(GATE.historyFrames), count: 0, index: 0 });
    let quietHistory = makeHistory(), allHistory = makeHistory();
    const pushHistory = (h, v) => { h.values[h.index] = v; h.index = (h.index + 1) % GATE.historyFrames; h.count++; };
    const percentile = (h, p) => {
        const values = Array.from(h.values.subarray(0, Math.min(h.count, GATE.historyFrames))).sort((a, b) => a - b);
        return values.length === 0 ? null : values[Math.min(values.length - 1, Math.floor(values.length * p))];
    };
    function resetGate(now = performance.now()) {
        Object.assign(level, { gate: false, raw: 0, toggles: 0, floor: -60, dbSmooth: -100, dbSlow: -100, calibrating: true });
        gateAboveFrames = 0; gateQuietSince = 0; framesSinceFloor = 0; lastMeasureAt = now;
        quietHistory = makeHistory(); allHistory = makeHistory();
        calibrateUntil = now + GATE.calibrateMs;
    }
    function measure(now) {
        if (!audio.stream || !audio.analyser) { level.raw = 0; level.db = -100; level.gate = false; return; }
        audio.analyser.getFloatTimeDomainData(audio.time);
        let sum = 0;
        for (const v of audio.time) sum += v * v;
        const rms = Math.sqrt(sum / audio.time.length);
        const db = level.db = rms > 0 ? Math.max(-100, 20 * Math.log10(rms)) : -100;
        const dt = Math.min(0.1, Math.max(0, (now - lastMeasureAt) / 1000)); lastMeasureAt = now;
        level.dbSmooth = level.dbSmooth <= -99 ? db : level.dbSmooth + (db - level.dbSmooth) * (1 - Math.exp(-dt / GATE.smoothTau));
        level.dbSlow = level.dbSlow <= -99 ? db : level.dbSlow + (db - level.dbSlow) * (1 - Math.exp(-dt / GATE.closeTau));
        const kept = clamp(db, GATE.floorMin - 10, 0);
        pushHistory(allHistory, kept);
        if (level.calibrating || !level.gate || kept < level.floor + GATE.closeDb + 2) pushHistory(quietHistory, kept);
        if (level.calibrating) {
            level.raw = 0;
            if (now >= calibrateUntil) {
                level.floor = clamp(percentile(quietHistory, 0.5) ?? -60, GATE.floorMin, GATE.floorMax);
                level.calibrating = false;
            }
            return;
        }
        if (++framesSinceFloor >= 6) {
            framesSinceFloor = 0;
            level.floor = clamp(percentile(quietHistory, GATE.quietPercentile) ?? level.floor, GATE.floorMin, GATE.floorMax);
        }
        const base = level.base = Math.max(level.floor, sens.minDb - GATE.openDb);
        const before = level.gate;
        if (!level.gate) {
            gateAboveFrames = level.dbSmooth > base + GATE.openDb ? gateAboveFrames + 1 : 0;
            if (gateAboveFrames >= GATE.openFrames) { level.gate = true; gateQuietSince = 0; }
        } else if (level.dbSlow < base + GATE.closeDb) {
            if (!gateQuietSince) gateQuietSince = now;
            if (now - gateQuietSince >= GATE.holdMs) { level.gate = false; gateAboveFrames = 0; }
        } else {
            gateQuietSince = 0;
        }
        if (before !== level.gate) level.toggles++;
        const x = clamp((db - (base + 8)) / (RANGE_DB * sens.range), 0, 1);
        level.raw = level.gate ? Math.pow(x, CURVE) : 0;
    }

    // ===== 見た目用のマイク（会話のマイクとは別） =====
    const notice = $("talk-notice");
    const micButton = $("talk-mic-toggle");
    let micRequestId = 0, micPending = false, micPendingSince = 0, lateStreamsStopped = 0;
    async function startVisualMic() {
        if (!navigator.mediaDevices?.getUserMedia || !(window.AudioContext || window.webkitAudioContext)) {
            notice.textContent = "この端末・ブラウザではマイクの音量を測れません。会話やほかの操作はそのまま使えます。";
            micButton.disabled = true;
            return;
        }
        // 使用中か、許可を待っている間は、新しい開始要求を出さない（連打で要求が重ならないように）。
        if (audio.stream || micPending) return;
        // 開始要求ごとに番号を付ける。止める・画面を離れると番号が進み、それより前の要求は無効になる。
        const requestId = ++micRequestId;
        micPending = true;
        micPendingSince = performance.now();
        showMicButton();
        try {
            if (!audio.ctx) {
                const Ctx = window.AudioContext || window.webkitAudioContext;
                audio.ctx = new Ctx();
                audio.analyser = audio.ctx.createAnalyser();
                audio.analyser.fftSize = 1024;
                audio.analyser.smoothingTimeConstant = 0.5;
                audio.time = new Float32Array(audio.analyser.fftSize);
            }
            if (audio.ctx.state === "suspended") await audio.ctx.resume();
            if (requestId !== micRequestId) return;
            notice.textContent = "マイクの許可を待っています（見た目のためだけに使います）。";
            // 試作と同じ条件：雑音除去・反響の除去はオン、自動の音量調整はオフ（声の大小がそのまま出るように）。
            const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: false } });
            // 無効になった要求（止めた・画面を離れた後）から遅れて入力が返ったら、全トラックをすぐ止める。使わない・自動で再開しない。
            if (requestId !== micRequestId || panel.hidden || document.hidden) {
                stream.getTracks().forEach(track => track.stop());
                lateStreamsStopped++;
                if (requestId === micRequestId) { micPending = false; showMicButton(); }
                return;
            }
            micPending = false;
            audio.stream = stream;
            audio.source = audio.ctx.createMediaStreamSource(stream);
            audio.source.connect(audio.analyser);   // スピーカーにはつながない
            resetGate();
            showMicButton();
            notice.textContent = "声の大きさだけを端末の中で測っています。録音・保存・送信はしていません。音声認識や送信には使いません。";
        } catch (error) {
            if (requestId !== micRequestId) return;   // 無効になった要求の失敗は、表示を変えない
            micPending = false;
            showMicButton();
            notice.textContent = error.name === "NotAllowedError"
                ? "マイクが許可されませんでした。見た目は演出だけで動きます。会話やほかの操作はそのまま使えます。"
                : `マイクを使えませんでした（${error.name}）。見た目は演出だけで動きます。会話やほかの操作はそのまま使えます。`;
        }
    }
    // 止める：使用中のマイクを止め、許可待ちの要求も無効にする。
    function stopVisualMic(message) {
        const wasActive = Boolean(audio.stream), wasPending = micPending;
        micRequestId++;
        micPending = false;
        audio.stream?.getTracks().forEach(track => track.stop());
        audio.source?.disconnect();
        audio.stream = null;
        audio.source = null;
        level.raw = 0; level.gate = false;
        showMicButton();
        if (wasActive || wasPending) notice.textContent = message ?? (wasActive ? "見た目用のマイクを止めました。" : "マイクの許可待ちを取り消しました。");
    }
    function showMicButton() {
        micButton.textContent = audio.stream ? "■ 反応を止める" : micPending ? "許可を待っています（もう一度押すと取り消し）" : "🎚 声に反応させる";
        micButton.setAttribute("aria-pressed", String(Boolean(audio.stream)));
    }
    // 許可待ちの直後（0.5秒以内）に重ねて押されたのは連打とみなして何もしない。それより後に押されたら、許可待ちを取り消す。
    micButton.addEventListener("click", () => {
        if (audio.stream) { stopVisualMic(); return; }
        if (micPending) {
            if (performance.now() - micPendingSince >= 500) stopVisualMic();
            return;
        }
        startVisualMic();
    });
    $("talk-sensitivity").addEventListener("change", event => { sens = SENSITIVITY[event.target.value] ?? SENSITIVITY.low; });

    // ===== 会話の開始・終了（既存のボタンを押すのと同じ。処理は app.js のまま） =====
    const sessionButton = $("voice-session-toggle");
    const talkSession = $("talk-session-toggle");
    talkSession.addEventListener("click", () => sessionButton.click());
    const syncSession = () => {
        talkSession.textContent = sessionButton.textContent;
        talkSession.disabled = sessionButton.disabled;
    };
    new MutationObserver(syncSession).observe(sessionButton, { childList: true, characterData: true, subtree: true, attributes: true, attributeFilter: ["disabled"] });
    syncSession();
    // 会話の案内（「次の発言を待っています」など）も写す。
    const voiceStatus = $("voice-status");
    const talkStatus = $("talk-voice-status");
    const syncStatus = () => { talkStatus.textContent = voiceStatus.textContent; };
    new MutationObserver(syncStatus).observe(voiceStatus, { childList: true, characterData: true, subtree: true });
    syncStatus();

    // ===== 字幕（デモ表示の字幕を写す。話者は「あなた」と、AI側は読み上げている発言の話者） =====
    // AI側の話者名は、app.js が字幕に付けた話者（ハル・ガク・AI将軍（確認））を写す。話者がない文は「AI将軍」。
    const userLine = $("demo-user-line"), aiLine = $("demo-ai-line");
    const subUser = $("talk-sub-user"), subAi = $("talk-sub-ai"), subAiWho = $("talk-sub-ai-who");
    const aiSpeaker = () => aiLine.dataset.speaker || "";
    const syncSubtitles = () => {
        subUser.textContent = userLine.textContent;
        subAi.textContent = aiLine.textContent;
        subAiWho.textContent = aiSpeaker() || "AI将軍";
        subAi.parentElement.dataset.speaker = aiSpeaker();
    };
    for (const line of [userLine, aiLine]) {
        new MutationObserver(syncSubtitles).observe(line, { childList: true, characterData: true, subtree: true, attributes: true, attributeFilter: ["data-speaker"] });
    }
    syncSubtitles();

    // ===== AI社員の状態と直近の実行ログ（司令本部の巡回のデータを描くだけ） =====
    const UNAVAILABLE_TEXT = "取得できません";
    const STATUS_LINES = { "長時間実行中": ["長時間実行中", "状態は要確認"], "エラー": ["失敗"] };
    function tag(status) {
        const span = document.createElement("span");
        span.className = "talk-tag";
        span.dataset.status = status;
        (STATUS_LINES[status] ?? [status]).forEach(text => {
            const line = document.createElement("span");
            line.className = "talk-tag-line";
            line.textContent = text;
            span.appendChild(line);
        });
        return span;
    }
    window.renderTalkPanels = (agents, activities) => {
        const agentBox = $("talk-agents"), logBox = $("talk-log");
        if (agents === null) {
            agentBox.textContent = UNAVAILABLE_TEXT;
        } else {
            agentBox.replaceChildren(...agents.map(agent => {
                const row = document.createElement("div");
                row.className = "talk-agent";
                row.dataset.status = agent.status;
                const name = document.createElement("strong");
                name.textContent = agent.agent;
                const last = document.createElement("span");
                last.className = "talk-agent-last";
                last.textContent = agent.lastAction || "まだ稼働記録がありません";
                row.append(tag(agent.status), name, last);
                return row;
            }));
        }
        if (activities === null) {
            logBox.textContent = UNAVAILABLE_TEXT;
        } else if (activities.length === 0) {
            logBox.textContent = "まだ実行記録がありません。";
        } else {
            logBox.replaceChildren(...activities.slice(0, 8).map(activity => {
                const row = document.createElement("div");
                row.className = "talk-log-row";
                const time = document.createElement("time");
                time.textContent = activity.startedAt ? activity.startedAt.slice(11, 16) : "";
                const body = document.createElement("span");
                const duration = activity.durationSeconds ? `（${activity.durationSeconds}秒）` : "";
                body.textContent = `${activity.agent}：${activity.action}${duration}` + (activity.detail ? ` — ${activity.detail}` : "");
                row.append(time, body, tag(activity.status));
                return row;
            }));
        }
    };

    // ===== 会話の状態の読み取り（app.js の値を読むだけ） =====
    function conversationMode() {
        const state = typeof currentVoiceState === "string" ? currentVoiceState : "idle";
        if (state === "thinking" || state === "sending") return "thinking";
        if (state === "speaking") return "speaking";
        if (state === "listening" || state === "confirming" || state === "waiting") return "listening";
        return "idle";
    }
    // AI社員の作業の印。ダッシュボードのオーブと同じ優先順で、文言も同じものを使う。
    function workLabel() {
        // app.js の最上位の変数は、別の script からも名前で読める（読むだけで、書き換えない）。
        const working = typeof workInProgress !== "undefined" && workInProgress;
        const long = typeof longRunningUnits !== "undefined" && longRunningUnits;
        const awaiting = typeof awaitingUnconfirmed !== "undefined" && awaitingUnconfirmed;
        if (working) return long ? "AI社員作業中・長時間の作業は要確認" : "AI社員作業中";
        if (awaiting) return long ? "操作の応答待ち・長時間の作業は要確認" : "操作の応答待ち・実行状態は未確認";
        if (long) return "長時間実行中・状態は要確認";
        return "";
    }

    // ===== 発光体（試作4版目と同じ描き方。散り方と、周りの粒子の雲だけ変える） =====
    // 散り方（本人が見比べて採用した「外側からはがれる」）：見えている輪郭の外側の粒子から順にはがれて飛び、内側ほど遅れて続く。
    // 周りの雲も、中心寄りは残り、外側の層だけがはがれる。
    // 音の実測と判定（声の強さの値）は変えない。その値から「どれだけ散らすか」への写し方だけを、同じ声でも前より大きく広がるようにした
    // （SCATTER_FROM〜SCATTER_TO。前は0.6〜0.9で、前に大声で出していた広がりが、今は普通に大きめの声で出る）。
    // 散ったあとの戻りだけ、見た目の上で約2秒かけて滑らかに戻す（peelLevel。声の強さの値そのもの＝level.smooth は変えない）。
    // 描画領域の四角い境界が見えないよう、粒子と雲は中心からの距離で丸く薄くする（多くの粒子が重なっても明るさが残らないよう、二乗で効かせる）。
    let W = 0, H = 0, particles = [], filaments = [], cloud = [];
    function makeParticles(n) {
        particles = [];
        const golden = Math.PI * (3 - Math.sqrt(5));
        for (let i = 0; i < n; i++) {
            const y = 1 - (i / (n - 1)) * 2, r = Math.sqrt(1 - y * y), th = golden * i;
            particles.push({ x: Math.cos(th) * r, y, z: Math.sin(th) * r, seed: Math.random() * 1000, band: Math.random(),
                fly: 0.3 + Math.pow(Math.random(), 1.6) * 2.4, ox: Math.random() * 2 - 1, oy: Math.random() * 2 - 1 });
        }
        filaments = Array.from({ length: n > 600 ? 16 : 10 }, () => ({ a: Math.random() * Math.PI * 2, b: Math.acos(2 * Math.random() - 1), seed: Math.random() * 1000, len: 0.6 + Math.random() * 0.6 }));
        makeCloud(Math.max(240, Math.round(n * 0.4)));
    }
    // 周りの粒子の雲（たっぷり）。オーブの輪郭のすぐ外から外側まで（短い辺の半分を1として0.5〜0.97。オーブとの間に空白ができないように）に、面積あたりむらなく散らばる位置
    // （ハルトン列。乱数よりも偏りが少ない）を割り当てる。rn は中心からの距離（同じ単位）。
    const halton = (i, base) => { let f = 1, r = 0; while (i > 0) { f /= base; r += f * (i % base); i = Math.floor(i / base); } return r; };
    const CLOUD_INNER = 0.5, CLOUD_OUTER = 0.97;
    function makeCloud(count) {
        cloud = [];
        for (let i = 1; i <= count; i++) {
            const a = halton(i, 2) * Math.PI * 2;
            const rn = Math.sqrt(CLOUD_INNER ** 2 + halton(i, 3) * (CLOUD_OUTER ** 2 - CLOUD_INNER ** 2));
            cloud.push({ a, rn, x: null, y: null, seed: Math.random() * 1000, size: 0.7 + Math.random() * 1.4, fly: 0.35 + Math.random() * 0.5 });
        }
    }
    // 中心からの距離に応じた薄さ（1：十分内側、0：短い辺の半分の位置より外）。丸く薄くなるので、四角い境界は出ない。
    const roundFade = (x, y, cx, cy, half) => { const f = 1 - smooth(half * 0.72, half, Math.hypot(x - cx, y - cy)); return f * f; };
    // 静かなときも、オーブの周りをむらなく覆ってゆっくり漂う。声の大きさで明るくなる。
    // 散るときは外側の層だけが外へはがれ（外側ほど先に、遠くへ）、中心寄りの雲は残るので、オーブとの間に空白ができない。
    // 戻るときも同じ順で、外側の層がゆっくり元の位置へ戻る。動きを減らす設定では描かない。
    function drawCloud(t, e, peel, cx, cy, half, hue) {
        if (reduced) return;
        for (const c of cloud) {
            const layer = smooth(0.78, CLOUD_OUTER, c.rn);
            const lag = (1 - layer) * 0.4;
            const lift = layer * smooth(0, 1, (peel - lag) / (1 - lag)) * c.fly;
            const wobble = noise(c.seed, t * 0.35) * 0.05;
            const r = (c.rn + lift + wobble) * half;
            const a = c.a + noise(c.seed + 5, t * 0.2) * 0.04;
            const x = cx + Math.cos(a) * r, y = cy + Math.sin(a) * r;
            const twinkle = 0.75 + 0.25 * Math.sin(t * 1.3 + c.seed);
            // 雲はまばらで重なりにくいので、粒子より外側から、ゆるやかに薄くする。
            const fade = 1 - smooth(half * 0.8, half, Math.hypot(x - cx, y - cy));
            const alpha = (0.32 + 0.4 * e) * twinkle * fade;
            if (alpha < 0.01) continue;
            const size = c.size * (1 + e * 0.5);
            g.fillStyle = `rgba(${hue},${Math.min(1, alpha)})`;
            g.fillRect(x - size / 2, y - size / 2, size, size);
        }
    }
    function resize() {
        const dpr = Math.min(window.devicePixelRatio || 1, 2);
        const rect = canvas.getBoundingClientRect();
        W = Math.max(1, rect.width); H = Math.max(1, rect.height);
        canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
        g.setTransform(dpr, 0, 0, dpr, 0, 0);
        const count = Math.min(W, H) < 420 || (navigator.hardwareConcurrency || 4) <= 4 ? 520 : 1100;
        if (particles.length !== count) makeParticles(count);
    }
    const noise = (s, t) => Math.sin(s * 1.7 + t * 1.3) * 0.5 + Math.sin(s * 3.1 - t * 2.1) * 0.3 + Math.sin(s * 5.3 + t * 0.7) * 0.2;
    const smooth = (a, b, v) => { const k = clamp((v - a) / (b - a), 0, 1); return k * k * (3 - 2 * k); };
    const view = { energy: 0, coreR: 0, spread: 0, scatter: 0, mode: "idle", source: "none" };
    let angle = 0, last = performance.now(), slowFrames = 0, lastTextAt = 0, running = false, speakPulse = 0, nextPulseAt = 0, peelLevel = 0, peelVelocity = 0;
    // 戻りの速さ（大きいほど速い）。2.5 で、声が止まってから約1秒で3割ほど、約2秒でほぼ元（4%ほど）まで戻る。
    const PEEL_RETURN_RATE = 2.5;
    // 声の強さ（0〜1）から散らばりへの写し方。この強さから散り始め、この強さでいちばん大きく散る。
    // 普通の声（模擬試験で約0.24）では散らさず、大きめの声（約0.5〜0.7）で、前の版の大声と同じくらいまで広がる。
    const SCATTER_FROM = 0.42, SCATTER_TO = 0.72;
    const SOURCE_TEXT = { mic: "マイクの実測音量", show: "演出（実測ではない）", none: "なし" };
    const SPEAKER_HUES = { "ハル": [255, 181, 71], "ガク": [62, 232, 181] };

    function frame(now) {
        if (!running) return;
        const dt = Math.min(0.05, (now - last) / 1000); last = now;
        const mode = conversationMode();
        // 反応の元：読み上げ中・考え中は演出。それ以外は、見た目用のマイクがオンなら実測、オフなら動かさない。
        // 読み上げ中はスピーカーの声をマイクが拾うことがあるため、実測は使わない。
        const source = mode === "speaking" || mode === "thinking" ? "show" : audio.stream ? "mic" : "none";
        if (audio.stream) measure(now); else level.raw = 0;
        let target = 0;
        if (source === "mic") target = level.raw;
        else if (mode === "speaking") {
            if (now >= nextPulseAt) { speakPulse = 0.6 + Math.random() * 0.4; nextPulseAt = now + 140 + Math.random() * 120; }
            speakPulse *= 0.92;
            target = 0.25 + speakPulse * 0.45;
        } else if (mode === "thinking") target = 0.25 + Math.sin(now / 600) * 0.08;
        const tau = target > level.smooth ? 0.05 : 0.4;
        level.smooth += (target - level.smooth) * (1 - Math.exp(-dt / tau));
        if (level.smooth < 0.002) level.smooth = 0;
        const e = level.smooth;
        Object.assign(view, { energy: e, mode, source });

        if (now - lastTextAt > 200) {
            lastTextAt = now;
            const state = typeof currentVoiceState === "string" ? currentVoiceState : "idle";
            $("talk-state").textContent = (typeof VOICE_STATE_LABELS === "object" && VOICE_STATE_LABELS[state]) || "待機中";
            $("talk-source").textContent = `反応の元：${SOURCE_TEXT[source]}`;
            $("talk-diag").textContent = source !== "mic" ? (audio.stream ? "見た目用のマイク：オン（いまは演出で動いています）" : "見た目用のマイク：オフ")
                : level.calibrating ? "周囲の音を測っています（0.8秒）"
                : `実測 ${level.db.toFixed(0)}dB／周囲の音 ${level.floor.toFixed(0)}dB（判定の基準 ${level.base.toFixed(0)}dB）／判定：${level.gate ? "話している" : "静か"}`;
            $("talk-meter").style.width = `${Math.round((source === "mic" ? level.raw : 0) * 100)}%`;
            const work = workLabel();
            const chip = $("talk-work");
            chip.hidden = !work;
            chip.textContent = work;
            subUser.parentElement.classList.toggle("is-current", mode === "listening");
            subAi.parentElement.classList.toggle("is-current", mode === "speaking");
        }

        // 読み上げ中は、話している発言の話者の色（ハルは橙、ガクは緑。AI将軍は今までの水色）。
        const hue = mode === "thinking" ? [120, 140, 255]
            : mode === "speaking" && SPEAKER_HUES[aiSpeaker()] ? SPEAKER_HUES[aiSpeaker()] : [63, 220, 255];
        view.hue = hue;
        const cx = W / 2, cy = H / 2;
        const R = Math.min(W, H) * 0.30 * (1 + e * 0.22);
        angle += dt * (reduced ? 0 : (mode === "thinking" ? 0.9 : 0.12 + e * 0.6));
        g.globalCompositeOperation = "destination-out";
        g.fillStyle = "rgba(0, 0, 0, 0.35)";
        g.fillRect(0, 0, W, H);
        g.globalCompositeOperation = "lighter";

        // 大きめの声で中心のオーブが散る（強さ SCATTER_FROM から散り始め、SCATTER_TO でいちばん大きく）。動きを減らす設定では散らさない。
        const scatter = reduced ? 0 : smooth(SCATTER_FROM, SCATTER_TO, e);
        view.scatter = scatter;
        const t = now / 1000;
        // はがれ具合の見た目。強くなるときはすぐ追う。弱くなるときは、急に吸い戻さないよう動き出しをなめらかにし
        // （ばねのような減衰）、声が止まってから約2秒でほぼ元へ戻す。
        const peelTarget = reduced ? 0 : smooth(SCATTER_FROM - 0.05, SCATTER_TO, e);
        if (peelTarget >= peelLevel) {
            peelLevel += (peelTarget - peelLevel) * (1 - Math.exp(-dt / 0.05));
            peelVelocity = 0;
        } else {
            const w = PEEL_RETURN_RATE;
            peelVelocity += (w * w * (peelTarget - peelLevel) - 2 * w * peelVelocity) * dt;
            peelLevel += peelVelocity * dt;
            if (peelLevel < peelTarget) { peelLevel = peelTarget; peelVelocity = 0; }
        }
        if (peelLevel < 0.002) peelLevel = 0;
        const half = Math.min(W, H) / 2;
        drawCloud(t, e, peelLevel, cx, cy, half, hue);
        const coreR = R * (0.22 + e * 0.18) * (1 - scatter * 0.55);
        view.coreR = coreR;
        const glow = g.createRadialGradient(cx, cy, 0, cx, cy, coreR * 3.2);
        glow.addColorStop(0, `rgba(255,255,255,${0.55 + e * 0.4})`);
        glow.addColorStop(0.18, `rgba(${hue},${0.55 + e * 0.35})`);
        glow.addColorStop(1, "rgba(0,0,0,0)");
        g.fillStyle = glow;
        g.beginPath(); g.arc(cx, cy, coreR * 3.2, 0, Math.PI * 2); g.fill();

        g.lineWidth = 1.2;
        for (const f of filaments) {
            // 光の線は、粒子が薄くなり始める位置より外へ伸ばさない（端で切れて見えないように）。
            const len = Math.min(R * f.len * (0.55 + e * 0.9), half * 0.72);
            const a = f.a + (reduced ? 0 : angle * 0.5);
            const dx = Math.cos(a) * Math.sin(f.b), dy = Math.cos(f.b) * 0.8;
            const wob = reduced ? 0 : noise(f.seed, t) * 0.35 * (0.3 + e);
            g.strokeStyle = `rgba(${hue},${0.10 + e * 0.45})`;
            g.beginPath();
            g.moveTo(cx, cy);
            g.quadraticCurveTo(cx + (dx + wob) * len * 0.5, cy + (dy - wob) * len * 0.5, cx + dx * len, cy + dy * len);
            g.stroke();
        }

        const cosA = Math.cos(angle), sinA = Math.sin(angle), tilt = 0.35;
        let spreadSum = 0;
        for (const p of particles) {
            let x = p.x * cosA - p.z * sinA, z = p.x * sinA + p.z * cosA, y = p.y;
            const y2 = y * Math.cos(tilt) - z * Math.sin(tilt); z = y * Math.sin(tilt) + z * Math.cos(tilt); y = y2;
            const n = reduced ? 0 : noise(p.seed, t * (mode === "thinking" ? 1.6 : 1));
            const push = 1 + n * (0.04 + e * 0.35) + (mode === "thinking" ? Math.sin(p.band * 12 + t * 2) * 0.05 : 0);
            const persp = 1.8 / (2.6 - z);
            // 画面上で輪郭に近い粒子ほど早く、内側ほど遅れてはがれる（戻るときは内側から先に戻る）。
            const delay = (1 - Math.min(1, Math.hypot(x, y))) * 0.4;
            const peel = smooth(0, 1, (peelLevel - delay) / (1 - delay));
            const fly = peel * p.fly * 1.4;
            const px = x * (push + fly) + p.ox * peel * 0.3;
            const py = y * (push + fly) + p.oy * peel * 0.3;
            spreadSum += Math.abs(push - 1) + fly;
            const sx = cx + px * R * persp;
            const sy = cy + py * R * persp;
            const fade = roundFade(sx, sy, cx, cy, half);
            if (fade <= 0) continue;
            const alpha = (0.15 + (z + 1) * 0.3) * (0.5 + e * 0.8) * fade;
            const size = (0.6 + (z + 1) * 0.7) * (1 + e * 0.8);
            g.fillStyle = `rgba(${hue},${Math.min(1, alpha)})`;
            g.fillRect(sx - size / 2, sy - size / 2, size, size);
        }
        view.spread = spreadSum / particles.length;

        slowFrames = dt > 0.028 ? slowFrames + 1 : Math.max(0, slowFrames - 1);
        if (slowFrames > 90 && particles.length > 300) { makeParticles(Math.round(particles.length * 0.7)); slowFrames = 0; }
        requestAnimationFrame(frame);
    }

    // 画面が見えている間だけ描く。会話画面を離れたら見た目用のマイクも止める。
    function onVisibility() {
        const visible = !panel.hidden && !document.hidden;
        if (visible && !running) {
            running = true;
            resize();
            last = performance.now();
            requestAnimationFrame(frame);
        } else if (!visible && running) {
            running = false;
        }
        // 使用中のマイクも、許可待ちの要求も止める（許可待ちの要求は無効になり、あとで入力が返っても使わない）。
        if (!visible && (audio.stream || micPending)) stopVisualMic(panel.hidden ? "会話画面を離れたため、見た目用のマイクを止めました。" : "画面を離れたため、見た目用のマイクを止めました。");
    }
    new MutationObserver(onVisibility).observe(panel, { attributes: true, attributeFilter: ["hidden"] });
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("pagehide", () => stopVisualMic());
    window.addEventListener("resize", () => { if (running) resize(); });
    onVisibility();

    // 試験用に、いまの状態を読めるようにする（画面の動きには使わない）。
    window.__talk = {
        level, view, GATE,
        get micActive() { return Boolean(audio.stream); },
        get micPending() { return micPending; },
        get lateStreamsStopped() { return lateStreamsStopped; },
        get micTracks() { return audio.stream ? audio.stream.getTracks().map(track => track.readyState) : []; },
        get running() { return running; },
        get sensitivity() { return Object.keys(SENSITIVITY).find(key => SENSITIVITY[key] === sens); },
        get cloudCount() { return cloud.length; },
        get peelLevel() { return peelLevel; },
        startVisualMic, stopVisualMic
    };
})();
