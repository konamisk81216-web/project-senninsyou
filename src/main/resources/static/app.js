async function loadTasks() {
    const taskList = document.getElementById("task-list");

    try {
        const response = await fetch("/api/tasks");
        const tasks = await response.json();
        populateRevenueTaskOptions(tasks);

        const totalTasks = document.getElementById("total-tasks");
        const inProgressTasks = document.getElementById("in-progress-tasks");
        const completedTasks = document.getElementById("completed-tasks");
        const notStartedTasks = document.getElementById("not-started-tasks");

        const inProgressCount = tasks.filter(task => task.status === "進行中").length;
        const completedCount = tasks.filter(task => task.status === "完了").length;
        const notStartedCount = tasks.filter(task => task.status === "未着手").length;

        totalTasks.textContent = tasks.length;
        inProgressTasks.textContent = inProgressCount;
        completedTasks.textContent = completedCount;
        notStartedTasks.textContent = notStartedCount;

        if (!tasks || tasks.length === 0) {
            renderEmptyState(taskList, "⚔️", "タスクはありません", "AI将軍の提案から最初のタスクを登録できます。");
            return;
        }

        taskList.innerHTML = "";

        renderHeadquartersTasks(tasks);

        tasks.forEach(task => {
            const taskCard = document.createElement("div");
            taskCard.className = `task-card priority-${priorityClass(task.priority)}`;

            const title = document.createElement("h3");
            title.textContent = task.taskName;

            const priority = document.createElement("span");
            priority.className = `priority-badge priority-${priorityClass(task.priority)}`;
            priority.textContent = `優先度 ${task.priority}`;

            const assignedAgent = document.createElement("p");
            assignedAgent.textContent = `担当：${task.assignedAgent ?? "未設定"}`;

            const status = document.createElement("p");
            status.textContent = `状態：${task.status}`;

            taskCard.append(title, priority, assignedAgent, status);

            const actions = document.createElement("div");
            actions.className = "task-actions";

            const editButton = document.createElement("button");
            editButton.className = "edit-button secondary-button";
            editButton.type = "button";
            editButton.textContent = "✎ 編集";
            editButton.addEventListener("click", () => {
                if (taskCard.querySelector(".task-edit-form")) return;
                const form = document.createElement("form");
                form.className = "task-edit-form";
                const nameLabel = document.createElement("label");
                nameLabel.textContent = "タスク名";
                const nameInput = document.createElement("input");
                nameInput.required = true;
                nameInput.maxLength = 200;
                nameInput.value = task.taskName;
                nameLabel.appendChild(nameInput);

                const priorityLabel = document.createElement("label");
                priorityLabel.textContent = "優先度";
                const prioritySelect = document.createElement("select");
                for (const value of ["高", "中", "低"]) {
                    const option = document.createElement("option");
                    option.value = value;
                    option.textContent = value;
                    prioritySelect.appendChild(option);
                }
                prioritySelect.value = task.priority;
                priorityLabel.appendChild(prioritySelect);

                const agentLabel = document.createElement("label");
                agentLabel.textContent = "担当";
                const agentInput = document.createElement("input");
                agentInput.maxLength = 100;
                agentInput.value = task.assignedAgent ?? "";
                agentLabel.appendChild(agentInput);

                const formActions = document.createElement("div");
                formActions.className = "task-actions";
                const saveButton = document.createElement("button");
                saveButton.type = "submit";
                saveButton.textContent = "変更を保存";
                const cancelButton = document.createElement("button");
                cancelButton.type = "button";
                cancelButton.className = "secondary-button";
                cancelButton.textContent = "キャンセル";
                cancelButton.addEventListener("click", () => form.remove());
                formActions.append(saveButton, cancelButton);
                form.append(nameLabel, priorityLabel, agentLabel, formActions);
                form.addEventListener("submit", async event => {
                    event.preventDefault();
                    saveButton.disabled = true;
                    try {
                        await updateTask(task.id, {
                            taskName: nameInput.value.trim(),
                            priority: prioritySelect.value,
                            assignedAgent: agentInput.value.trim()
                        });
                        await loadTasks();
                    } catch (error) {
                        alert("タスクの変更を保存できませんでした。");
                        console.error(error);
                        saveButton.disabled = false;
                    }
                });
                taskCard.insertBefore(form, actions);
                nameInput.focus();
            });
            actions.appendChild(editButton);

            const nextStatus = getNextStatus(task.status);

            if (nextStatus !== null) {
                const statusButton = document.createElement("button");
                statusButton.className = "status-button";
                statusButton.textContent = nextStatus === "進行中"
                    ? "▶ 進行開始"
                    : "✅ 完了にする";

                statusButton.addEventListener("click", async () => {
                    statusButton.disabled = true;

                    try {
                        await updateTaskStatus(task.id, nextStatus);
                        await loadTasks();
                    } catch (error) {
                        alert("タスク状態の更新に失敗しました。");
                        console.error(error);
                        statusButton.disabled = false;
                    }
                });

                actions.appendChild(statusButton);
            }

            const deleteButton = document.createElement("button");
            deleteButton.className = "delete-button";
            deleteButton.textContent = "🗑 タスクを削除";

            deleteButton.addEventListener("click", async () => {
                const shouldDelete = window.confirm(
                    `タスク「${task.taskName}」を削除しますか？\nこの操作は元に戻せません。`
                );

                if (!shouldDelete) {
                    return;
                }

                deleteButton.disabled = true;

                try {
                    await deleteTask(task.id);
                    await loadTasks();
                } catch (error) {
                    alert("タスクの削除に失敗しました。");
                    console.error(error);
                    deleteButton.disabled = false;
                }
            });

            actions.appendChild(deleteButton);
            taskCard.appendChild(actions);
            taskList.appendChild(taskCard);
        });

    } catch (error) {
        taskList.innerHTML = "<p>タスクの取得に失敗しました。</p>";
        console.error(error);
    }
}

function priorityClass(priority) {
    if (priority === "高") return "high";
    if (priority === "低") return "low";
    return "medium";
}

function renderHeadquartersTasks(tasks) {
    const container = document.getElementById("hq-priority-tasks");
    container.replaceChildren();

    const priorityOrder = { "高": 0, "中": 1, "低": 2 };
    const activeTasks = tasks
        .filter(task => task.status !== "完了")
        .sort((left, right) =>
            (priorityOrder[left.priority] ?? 9) - (priorityOrder[right.priority] ?? 9))
        .slice(0, 3);

    if (activeTasks.length === 0) {
        container.textContent = "現在、着手すべきタスクはありません。";
        return;
    }

    activeTasks.forEach((task, index) => {
        const item = document.createElement("article");
        item.className = `hq-task-item priority-${priorityClass(task.priority)}`;
        const rank = document.createElement("strong");
        rank.textContent = index === 0 ? "最優先" : `${index + 1}番手`;
        const content = document.createElement("div");
        const title = document.createElement("h4");
        title.textContent = task.taskName;
        const detail = document.createElement("p");
        detail.textContent = `${task.status}・${task.assignedAgent ?? "担当未設定"}`;
        content.append(title, detail);
        item.append(rank, content);
        container.appendChild(item);
    });
}

function getNextStatus(currentStatus) {
    if (currentStatus === "未着手") {
        return "進行中";
    }

    if (currentStatus === "進行中") {
        return "完了";
    }

    return null;
}

async function updateTaskStatus(taskId, status) {
    const response = await fetch("/api/tasks/status", {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            id: String(taskId),
            status
        })
    });

    if (!response.ok) {
        throw new Error(await response.text());
    }
}

async function updateTask(taskId, changes) {
    const response = await fetch(`/api/tasks/${taskId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(changes)
    });
    if (!response.ok) throw new Error(await response.text());
}

async function deleteTask(taskId) {
    const response = await fetch(`/api/tasks/${taskId}`, {
        method: "DELETE"
    });

    if (!response.ok) {
        throw new Error(await response.text());
    }
}

function populateRevenueTaskOptions(tasks) {
    const taskSelect = document.getElementById("revenue-task");
    const selectedValue = taskSelect.value;

    taskSelect.replaceChildren();

    const emptyOption = document.createElement("option");
    emptyOption.value = "";
    emptyOption.textContent = "タスクと関連付けない";
    taskSelect.appendChild(emptyOption);

    tasks.forEach(task => {
        const option = document.createElement("option");
        option.value = task.id;
        option.textContent = task.taskName;
        taskSelect.appendChild(option);
    });

    taskSelect.value = selectedValue;
}

function formatYen(value) {
    return new Intl.NumberFormat("ja-JP", {
        style: "currency",
        currency: "JPY",
        maximumFractionDigits: 0
    }).format(value ?? 0);
}

function formatWorkTime(totalMinutes) {
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    return hours > 0 ? `${hours}時間 ${minutes}分` : `${minutes}分`;
}

async function loadOpportunities() {
    const list = document.getElementById("opportunity-list");

    try {
        const response = await fetch("/api/opportunities");
        if (!response.ok) {
            throw new Error(await response.text());
        }
        renderOpportunities(await response.json());
    } catch (error) {
        list.textContent = "収益機会の取得に失敗しました。";
        console.error(error);
    }
}

function renderOpportunities(opportunities) {
    const list = document.getElementById("opportunity-list");
    list.replaceChildren();

    if (opportunities.length === 0) {
        renderEmptyState(list, "📡", "収益機会はありません", "上のフォームから最初の候補をレーダーへ登録しましょう。");
        return;
    }

    opportunities.forEach((opportunity, index) => {
        const card = document.createElement("article");
        card.className = `opportunity-card risk-${opportunity.riskLevel}`;

        const heading = document.createElement("div");
        heading.className = "opportunity-card-heading";
        const title = document.createElement("h4");
        title.textContent = `${index + 1}位　${opportunity.title}`;
        const type = document.createElement("span");
        type.textContent = opportunity.opportunityType;
        heading.append(title, type);

        const metrics = document.createElement("div");
        metrics.className = "opportunity-metrics";
        const hourly = opportunity.expectedRevenuePerHour == null
            ? "算出不可"
            : formatYen(opportunity.expectedRevenuePerHour);
        [
            ["想定収益", formatYen(opportunity.expectedRevenue)],
            ["必要時間", formatWorkTime(opportunity.estimatedMinutes)],
            ["想定時給", hourly],
            ["リスク", opportunity.riskLevel],
            ["比較スコア", opportunity.comparisonScore.toLocaleString("ja-JP")]
        ].forEach(([label, value]) => {
            const metric = document.createElement("div");
            const labelElement = document.createElement("span");
            const valueElement = document.createElement("strong");
            labelElement.textContent = label;
            valueElement.textContent = value;
            metric.append(labelElement, valueElement);
            metrics.appendChild(metric);
        });

        if (opportunity.notes) {
            const notes = document.createElement("p");
            notes.className = "opportunity-notes";
            notes.textContent = opportunity.notes;
            card.append(heading, metrics, notes);
        } else {
            card.append(heading, metrics);
        }

        if (opportunity.linkedTaskId != null) {
            const trace = document.createElement("div");
            trace.className = "opportunity-trace";

            const traceTitle = document.createElement("h5");
            traceTitle.textContent = "🔗 タスク・実績トレーサビリティ";
            const taskState = document.createElement("p");
            taskState.textContent = `関連タスク：${opportunity.linkedTaskName}（${opportunity.linkedTaskStatus}）`;

            const traceMetrics = document.createElement("div");
            traceMetrics.className = "opportunity-trace-metrics";
            [
                ["想定収益", formatYen(opportunity.expectedRevenue)],
                ["実売上", formatYen(opportunity.actualRevenue)],
                ["実利益", formatYen(opportunity.actualProfit)],
                ["想定との差", formatSignedYen(opportunity.revenueVariance)]
            ].forEach(([label, value]) => {
                const metric = document.createElement("div");
                const labelElement = document.createElement("span");
                const valueElement = document.createElement("strong");
                labelElement.textContent = label;
                valueElement.textContent = value;
                metric.append(labelElement, valueElement);
                traceMetrics.appendChild(metric);
            });

            trace.append(traceTitle, taskState, traceMetrics);
            card.appendChild(trace);
        }

        const actions = document.createElement("div");
        actions.className = "opportunity-actions";
        const statusSelect = document.createElement("select");
        ["未評価", "調査中", "有望", "保留", "却下"].forEach(status => {
            const option = document.createElement("option");
            option.value = status;
            option.textContent = status;
            option.selected = status === opportunity.status;
            statusSelect.appendChild(option);
        });
        statusSelect.addEventListener("change", async () => {
            await updateOpportunityStatus(opportunity.id, statusSelect.value);
            await loadOpportunities();
        });
        actions.appendChild(statusSelect);

        if (opportunity.status === "有望" && opportunity.linkedTaskId == null) {
            const taskButton = document.createElement("button");
            taskButton.type = "button";
            taskButton.textContent = "⚔ タスクとして登録";
            taskButton.addEventListener("click", () => createTaskFromOpportunity(opportunity, taskButton));
            actions.appendChild(taskButton);
        }

        const deleteButton = document.createElement("button");
        deleteButton.type = "button";
        deleteButton.className = "delete-button";
        deleteButton.textContent = "削除";
        deleteButton.addEventListener("click", async () => {
            if (!window.confirm(`収益機会「${opportunity.title}」を削除しますか？`)) {
                return;
            }
            const response = await fetch(`/api/opportunities/${opportunity.id}`, { method: "DELETE" });
            if (!response.ok) {
                alert("収益機会の削除に失敗しました。");
                return;
            }
            await loadOpportunities();
        });
        actions.appendChild(deleteButton);
        card.appendChild(actions);
        list.appendChild(card);
    });
}

async function updateOpportunityStatus(id, status) {
    const response = await fetch(`/api/opportunities/${id}/status`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status })
    });
    if (!response.ok) {
        throw new Error(await response.text());
    }
}

async function createTaskFromOpportunity(opportunity, button) {
    if (!window.confirm(`「${opportunity.title}」をタスクとして登録しますか？`)) {
        return;
    }

    button.disabled = true;
    try {
        const response = await fetch(`/api/opportunities/${opportunity.id}/task`, {
            method: "POST"
        });
        if (!response.ok) {
            throw new Error(await response.text());
        }
        button.textContent = "✅ タスク登録済み";
        await Promise.all([loadTasks(), loadOpportunities()]);
    } catch (error) {
        alert("タスクの登録に失敗しました。");
        button.disabled = false;
        console.error(error);
    }
}

function formatSignedYen(value) {
    const number = Number(value ?? 0);
    const prefix = number > 0 ? "+" : "";
    return `${prefix}${formatYen(number)}`;
}

async function loadRevenueDashboard() {
    const revenueList = document.getElementById("revenue-list");

    try {
        const [summaryResponse, recordsResponse, analysisResponse] = await Promise.all([
            fetch("/api/revenue/summary"),
            fetch("/api/revenue"),
            fetch("/api/revenue/task-analysis")
        ]);

        if (!summaryResponse.ok || !recordsResponse.ok || !analysisResponse.ok) {
            throw new Error("収益情報の取得に失敗しました。");
        }

        const summary = await summaryResponse.json();
        const records = await recordsResponse.json();
        const analyses = await analysisResponse.json();

        document.getElementById("total-revenue").textContent =
            formatYen(summary.totalRevenue);
        document.getElementById("total-expense").textContent =
            formatYen(summary.totalExpense);
        document.getElementById("total-profit").textContent =
            formatYen(summary.totalProfit);
        document.getElementById("total-work-time").textContent =
            formatWorkTime(summary.totalWorkMinutes);
        document.getElementById("hq-total-revenue").textContent =
            formatYen(summary.totalRevenue);
        document.getElementById("hq-total-profit").textContent =
            formatYen(summary.totalProfit);
        document.getElementById("hq-total-work-time").textContent =
            formatWorkTime(summary.totalWorkMinutes);

        renderRevenueRecords(records);
        renderTaskRevenueAnalysis(analyses);
    } catch (error) {
        revenueList.textContent = "収益記録の取得に失敗しました。";
        console.error(error);
    }
}

function formatPercent(value) {
    return value == null ? "算出不可" : `${Number(value).toLocaleString("ja-JP")}%`;
}

function renderTaskRevenueAnalysis(analyses) {
    const container = document.getElementById("task-revenue-analysis");
    container.replaceChildren();

    if (analyses.length === 0) {
        renderEmptyState(container, "📊", "分析データがありません", "タスクに関連付けた実績を記録すると、効率とROIを比較できます。");
        return;
    }

    analyses.forEach((analysis, index) => {
        const card = document.createElement("article");
        card.className = "task-analysis-card";

        const heading = document.createElement("div");
        heading.className = "task-analysis-card-heading";

        const title = document.createElement("h4");
        title.textContent = `${index + 1}位　${analysis.taskName}`;

        const count = document.createElement("span");
        count.textContent = `${analysis.recordCount}件の実績`;
        heading.append(title, count);

        const metrics = document.createElement("div");
        metrics.className = "task-analysis-metrics";

        const metricValues = [
            ["利益", formatYen(analysis.totalProfit)],
            ["作業時間", formatWorkTime(analysis.totalWorkMinutes)],
            ["1時間あたり利益", analysis.profitPerHour == null ? "算出不可" : formatYen(analysis.profitPerHour)],
            ["利益率", formatPercent(analysis.profitMarginPercent)],
            ["投資ROI", formatPercent(analysis.investmentRoiPercent)]
        ];

        metricValues.forEach(([label, value]) => {
            const metric = document.createElement("div");
            const labelElement = document.createElement("span");
            const valueElement = document.createElement("strong");
            labelElement.textContent = label;
            valueElement.textContent = value;
            metric.append(labelElement, valueElement);
            metrics.appendChild(metric);
        });

        card.append(heading, metrics);
        container.appendChild(card);
    });
}

function renderRevenueRecords(records) {
    const revenueList = document.getElementById("revenue-list");
    revenueList.replaceChildren();

    if (records.length === 0) {
        renderEmptyState(revenueList, "💰", "実績はまだありません", "最初の売上・経費・作業時間を記録しましょう。");
        return;
    }

    records.forEach(record => {
        const card = document.createElement("article");
        card.className = "revenue-record";

        const title = document.createElement("h4");
        title.textContent = record.description;

        const result = document.createElement("p");
        result.className = "revenue-result";
        result.textContent =
            `売上 ${formatYen(record.revenue)} − 経費 ${formatYen(record.expense)} = 利益 ${formatYen(record.profit)}`;

        const details = document.createElement("p");
        details.textContent =
            `${record.occurredOn}・作業 ${formatWorkTime(record.workMinutes)}`;

        card.append(title, result, details);

        if (record.notes) {
            const notes = document.createElement("p");
            notes.textContent = record.notes;
            card.appendChild(notes);
        }

        const deleteButton = document.createElement("button");
        deleteButton.className = "delete-button revenue-delete";
        deleteButton.type = "button";
        deleteButton.textContent = "記録を削除";

        deleteButton.addEventListener("click", async () => {
            if (!window.confirm(`収益記録「${record.description}」を削除しますか？`)) {
                return;
            }

            deleteButton.disabled = true;

            try {
                const response = await fetch(`/api/revenue/${record.id}`, {
                    method: "DELETE"
                });

                if (!response.ok) {
                    throw new Error(await response.text());
                }

                await Promise.all([
                    loadRevenueDashboard(),
                    loadOpportunities()
                ]);
            } catch (error) {
                alert("収益記録の削除に失敗しました。");
                console.error(error);
                deleteButton.disabled = false;
            }
        });

        card.appendChild(deleteButton);
        revenueList.appendChild(card);
    });
}

    loadTasks();
    loadOpportunities();
    loadRevenueDashboard();

    const conversation = [];

async function sendCommand(mode = "discuss", options = {}) {
    const input = document.getElementById("command-input");
    const command = mode === "propose"
        ? "これまで話した内容を踏まえ、登録前に確認するタスク案を1件まとめて。"
        : input.value.trim();

    if (command === "") {
        alert("相談したいことを入力してください。");
        return;
    }

    const commandButton = document.getElementById("command-button");
    const proposalButton = document.getElementById("propose-task-button");
    const status = document.getElementById("command-status");
    commandButton.disabled = true;
    proposalButton.disabled = true;
    voiceInputButton.disabled = true;
    commandButton.textContent = "考え中...";
    status.textContent = mode === "propose" ? "タスク案を整理しています..." : "新しい返答を作成中です。下は前回の会話です。";
    status.hidden = false;
    document.getElementById("ai-response").classList.add("is-thinking");
    try {
        const response = await fetch("/api/ai/command", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                message: command,
                history: conversation.slice(-8).map(item => `${item.role}: ${item.text}`).join("\n"),
                mode
            })
        });

        if (!response.ok) throw new Error(await response.text());
        const data = await response.json();

        const aiResponse = document.getElementById("ai-response");
        aiResponse.querySelectorAll(".ai-result").forEach(element => element.remove());
        if (mode !== "propose") {
            appendConversationMessage(aiResponse, "あなた", command);
        }
        const answer = data.summary ?? "回答を受け取れませんでした。";
        appendConversationMessage(aiResponse, "AI将軍", answer, data, mode);
        conversation.push({role: "利用者", text: command}, {role: "AI将軍", text: answer});
        document.getElementById("voice-read-button").hidden = false;
        if (options.speak) {
            if (window.speechSynthesis) {
                speakAnswer(answer);
            } else {
                voiceStatus.textContent = "返答は表示されましたが、このブラウザでは自動読み上げに対応していません。";
            }
        }
        proposalButton.hidden = false;
        document.getElementById("continue-conversation-button").hidden = false;

        const approveButton = mode === "propose" ? renderAiResponse(aiResponse, data) : null;

        if (approveButton) {
            approveButton.addEventListener("click", async () => {
                try {
                    const saveResponse = await fetch("/api/tasks", {
                        method: "POST",
                        headers: {
                            "Content-Type": "application/json"
                        },
                        body: JSON.stringify({
                            taskName: data.nextTask,
                            priority: data.priority,
                            assignedAgent: data.assignedAgent
                        })
                    });

                    if (!saveResponse.ok) {
                        throw new Error("タスク登録に失敗しました。");
                    }

                    approveButton.disabled = true;
                    approveButton.textContent = "✅ 登録済み";

                    await loadTasks();

                } catch (error) {
                    alert("タスクの登録に失敗しました。");
                    console.error(error);
                }
            });
        }

        if (mode !== "propose") input.value = "";

    } catch (error) {
        if (options.speak) voiceStatus.textContent = "AI将軍の返答を取得できませんでした。もう一度試してください。";
        alert("AI将軍への送信に失敗しました。");
        console.error(error);
    } finally {
        commandButton.disabled = false;
        proposalButton.disabled = false;
        voiceInputButton.disabled = !SpeechRecognitionClass;
        commandButton.textContent = "将軍と話す";
        status.hidden = true;
        document.getElementById("ai-response").classList.remove("is-thinking");
    }
}

function appendConversationMessage(container, speaker, message, data, mode) {
    if (!container.querySelector(".conversation-message")) container.replaceChildren();
    const entry = document.createElement("article");
    entry.className = `conversation-message ${speaker === "あなた" ? "from-user" : "from-general"}`;
    const label = document.createElement("strong");
    label.textContent = speaker;
    const text = document.createElement("p");
    text.textContent = message;
    entry.append(label, text);
    if (data && mode !== "propose") {
        const details = document.createElement("details");
        details.className = "conversation-insights";
        const summary = document.createElement("summary");
        summary.textContent = "判断の内訳を見る";
        const grid = document.createElement("div");
        grid.className = "conversation-insight-grid";
        grid.append(
            createInsightCard("💰", "収益分析", data.revenueInsight, "revenue"),
            createInsightCard("📊", "実績分析", data.performanceDecision, "analysis"),
            createInsightCard("🚀", "市場機会", data.marketOpportunity, "market"));
        details.append(summary, grid);
        entry.appendChild(details);
    }
    container.appendChild(entry);
}

const commandButton = document.getElementById("command-button");
const commandInput = document.getElementById("command-input");
const voiceInputButton = document.getElementById("voice-input-button");
const voiceReadButton = document.getElementById("voice-read-button");
const voiceStopButton = document.getElementById("voice-stop-button");
const voiceStatus = document.getElementById("voice-status");
const SpeechRecognitionClass = window.SpeechRecognition || window.webkitSpeechRecognition;
let voiceRecognition = null;
let voiceListening = false;
let voiceRecognized = false;
let voiceCanceled = false;
let voiceError = false;

if (!SpeechRecognitionClass) {
    voiceInputButton.disabled = true;
    voiceStatus.textContent = "このブラウザは声での入力に対応していません。文字入力は引き続き使えます。";
}
if (!window.speechSynthesis) {
    voiceReadButton.disabled = true;
}

voiceInputButton.addEventListener("click", () => {
    if (!SpeechRecognitionClass) return;
    if (voiceListening) {
        voiceCanceled = true;
        voiceRecognition.stop();
        return;
    }
    voiceRecognized = false;
    voiceCanceled = false;
    voiceError = false;
    voiceRecognition = new SpeechRecognitionClass();
    voiceRecognition.lang = "ja-JP";
    voiceRecognition.continuous = false;
    voiceRecognition.interimResults = false;
    voiceRecognition.onstart = () => {
        voiceListening = true;
        voiceInputButton.textContent = "■ 聞き取り停止";
        voiceStatus.textContent = "聞き取り中... 話し終わると自動で将軍へ送ります。停止すると送信しません。";
    };
    voiceRecognition.onresult = event => {
        const transcript = Array.from(event.results)
            .filter(result => result.isFinal)
            .map(result => result[0].transcript)
            .join(" ").trim();
        if (transcript) {
            commandInput.value = transcript.slice(0, 2000);
            voiceRecognized = true;
            voiceStatus.textContent = "聞き取りました。将軍へ送信します...";
        }
    };
    voiceRecognition.onerror = event => {
        voiceCanceled = true;
        voiceError = true;
        voiceStatus.textContent = event.error === "not-allowed"
            ? "マイクの利用が許可されませんでした。ブラウザの権限を確認してください。"
            : "音声を聞き取れませんでした。もう一度試すか、文字で入力してください。";
    };
    voiceRecognition.onend = () => {
        voiceListening = false;
        voiceInputButton.textContent = "🎙️ 声で入力";
        if (voiceRecognized && !voiceCanceled) {
            sendCommand("discuss", { speak: true });
            return;
        }
        if (voiceCanceled && !voiceError) {
            voiceStatus.textContent = "音声入力を停止しました。送信していません。";
            return;
        }
        if (voiceError) return;
        if (!voiceRecognized && voiceStatus.textContent.startsWith("聞き取り中")) {
            voiceStatus.textContent = "音声が認識されませんでした。もう一度試してください。";
        }
    };
    try {
        voiceRecognition.start();
    } catch (error) {
        voiceStatus.textContent = "音声入力を開始できませんでした。文字入力をお使いください。";
    }
});

function speakAnswer(answer) {
    if (!answer || !window.speechSynthesis) return;
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(answer);
    utterance.lang = "ja-JP";
    utterance.rate = 1;
    utterance.onstart = () => {
        voiceStopButton.hidden = false;
        voiceStatus.textContent = "AI将軍の返答を読み上げています。";
    };
    utterance.onend = () => {
        voiceStopButton.hidden = true;
        voiceStatus.textContent = "読み上げが終わりました。";
    };
    utterance.onerror = event => {
        if (event.error === "interrupted" || event.error === "canceled") return;
        voiceStopButton.hidden = true;
        voiceStatus.textContent = "読み上げに失敗しました。画面の返答を確認してください。";
    };
    window.speechSynthesis.speak(utterance);
}

voiceReadButton.addEventListener("click", () => {
    speakAnswer(conversation.at(-1)?.text);
});

voiceStopButton.addEventListener("click", () => {
    window.speechSynthesis.cancel();
    voiceStopButton.hidden = true;
    voiceStatus.textContent = "読み上げを停止しました。";
});
const revenueForm = document.getElementById("revenue-form");
const revenueDate = document.getElementById("revenue-date");
const opportunityForm = document.getElementById("opportunity-form");
const pageTitle = document.getElementById("page-title");
const navButtons = document.querySelectorAll(".nav-button");
const pagePanels = document.querySelectorAll("[data-page-panel]");

const pageTitles = {
    command: "ダッシュボード",
    tasks: "タスク管理",
    note: "note販売",
    opportunities: "機会発見レーダー",
    revenue: "収益・実績分析",
    video: "動画制作・確認"
};

function showPage(page) {
    navButtons.forEach(button => {
        const active = button.dataset.page === page;
        button.classList.toggle("active", active);
        if (active) {
            button.setAttribute("aria-current", "page");
        } else {
            button.removeAttribute("aria-current");
        }
    });

    pagePanels.forEach(panel => {
        panel.hidden = panel.dataset.pagePanel !== page;
    });

    pageTitle.textContent = pageTitles[page] ?? "ダッシュボード";
    if (page === "note") loadNoteWorkflow();
    window.scrollTo({ top: 0, behavior: "smooth" });
}

const noteStageNames = ["企画", "制作", "公開", "販売", "実績"];
const noteNextActions = {
    "企画": "誰に何を売るかを決め、需要・競合・価格の仮説を確認する。",
    "制作": "記事の構成を作り、本文・画像・販売ページを仕上げる。",
    "公開": "内容と権利を確認してから、本人がnoteで公開する。",
    "販売": "告知経路を選び、SNSや導線を試し、反応を記録する。",
    "実績": "実際の売上・経費・作業時間を記録し、改善点を決める。"
};
const noteStageChecks = {
    "企画": ["対象読者と悩みを決めた", "記事の価値と価格を決めた", "競合との差を確認した"],
    "制作": ["記事本文を仕上げた", "画像と販売ページを整えた", "誤字・事実関係を確認した"],
    "公開": ["記事内容を最終確認した", "画像・引用・権利を確認した", "本人がnoteで公開し、URLを確認した"],
    "販売": ["SNS用の投稿文を用意した", "投稿内容を本人が確認した", "告知して反応を記録した"],
    "実績": ["売上と経費を記録した", "作業時間を記録した", "次の改善点を決めた"]
};
let noteWorkflowData = null;

async function loadNoteWorkflow() {
    const status = document.getElementById("note-task-status");
    status.textContent = "進行状況を読み込み中...";
    try {
        const responses = await Promise.all([
            fetch("/api/tasks"), fetch("/api/opportunities"), fetch("/api/revenue")
        ]);
        if (responses.some(response => !response.ok)) throw new Error("進行状況を取得できませんでした。");
        const [tasks, opportunities, records] = await Promise.all(responses.map(response => response.json()));
        if (!Array.isArray(tasks) || !Array.isArray(opportunities) || !Array.isArray(records)) {
            throw new Error("進行状況の形式が正しくありません。");
        }
        noteWorkflowData = { tasks, opportunities, records };
        const taskSelect = document.getElementById("note-task-select");
        const priorId = Number(taskSelect.value);
        taskSelect.replaceChildren();
        for (const task of tasks) {
            const option = document.createElement("option");
            option.value = task.id;
            option.textContent = task.taskName;
            taskSelect.appendChild(option);
        }
        const selected = tasks.find(task => task.id === priorId)
            ?? tasks.find(task => /note/i.test(task.taskName)) ?? tasks[0];
        if (!selected) {
            status.textContent = "タスクがありません。まずAI将軍と相談してnote販売のタスクを登録してください。";
            document.getElementById("note-stages").replaceChildren();
            document.getElementById("note-stage-checklist").replaceChildren();
            document.getElementById("note-next-text").textContent = "タスク登録後に進行段階を管理できます。";
            document.getElementById("note-stage-save").disabled = true;
            document.getElementById("note-stage-next").disabled = true;
            return;
        }
        taskSelect.value = String(selected.id);
        document.getElementById("note-stage-save").disabled = false;
        renderNoteWorkflow();
    } catch (error) {
        status.textContent = "進行状況を読み込めませんでした。DB更新が済んでいるか確認してください。";
        console.error(error);
    }
}

function renderNoteWorkflow() {
    if (!noteWorkflowData) return;
    const taskId = Number(document.getElementById("note-task-select").value);
    const task = noteWorkflowData.tasks.find(item => item.id === taskId);
    if (!task) return;
    const stage = noteStageNames.includes(task.noteStage) ? task.noteStage : "企画";
    const stageIndex = noteStageNames.indexOf(stage);
    document.getElementById("note-task-status").textContent =
        `状態：${task.status}　担当：${task.assignedAgent ?? "未設定"}`;
    const writerTheme = document.getElementById("writer-theme");
    if (!writerTheme.value.trim()) writerTheme.value = task.taskName;
    document.getElementById("note-stage-select").value = stage;
    const nextButton = document.getElementById("note-stage-next");
    const nextStage = noteStageNames[stageIndex + 1];
    nextButton.disabled = true;
    nextButton.textContent = nextStage
        ? `「${stage}」を完了して「${nextStage}」へ進む`
        : "最終段階です";
    document.getElementById("note-next-text").textContent = noteNextActions[stage];
    renderNoteStageChecklist(stage, Boolean(nextStage));
    const stages = document.getElementById("note-stages");
    stages.replaceChildren();
    noteStageNames.forEach((name, index) => {
        const item = document.createElement("div");
        item.className = `note-stage ${index === stageIndex ? "current" : index < stageIndex ? "passed" : "future"}`;
        item.textContent = `${index < stageIndex ? "✓ " : `${index + 1}. `}${name}${index === stageIndex ? "（現在）" : ""}`;
        stages.appendChild(item);
    });
    const opportunityList = document.getElementById("note-opportunity-list");
    opportunityList.replaceChildren();
    const linkedOpportunities = noteWorkflowData.opportunities.filter(item => item.linkedTaskId === taskId);
    if (linkedOpportunities.length === 0) opportunityList.textContent = "関連付けられた機会はまだありません。";
    for (const item of linkedOpportunities) {
        const row = document.createElement("p");
        row.textContent = `${item.title}／${item.status}／想定 ${formatYen(item.expectedRevenue)}`;
        opportunityList.appendChild(row);
    }
    const revenueList = document.getElementById("note-revenue-list");
    revenueList.replaceChildren();
    const linkedRecords = noteWorkflowData.records.filter(item => item.taskId === taskId);
    if (linkedRecords.length === 0) revenueList.textContent = "関連付けられた実績はまだありません。";
    for (const item of linkedRecords) {
        const row = document.createElement("p");
        row.textContent = `${item.description}／売上 ${formatYen(item.revenue)}／利益 ${formatYen(item.profit)}`;
        revenueList.appendChild(row);
    }
}

function renderNoteStageChecklist(stage, canAdvance) {
    const checklist = document.getElementById("note-stage-checklist");
    checklist.replaceChildren();
    const heading = document.createElement("h4");
    heading.textContent = canAdvance ? "完了前の確認" : "最終確認";
    checklist.appendChild(heading);
    for (const [index, text] of noteStageChecks[stage].entries()) {
        const label = document.createElement("label");
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.dataset.stageCheck = String(index);
        label.append(checkbox, document.createTextNode(text));
        checklist.appendChild(label);
    }
    if (!canAdvance) {
        const note = document.createElement("p");
        note.textContent = "この段階で販売結果を振り返ります。タスク完了や売上登録は自動では行いません。";
        checklist.appendChild(note);
    }
}

document.getElementById("note-stage-checklist").addEventListener("change", () => {
    const checks = [...document.querySelectorAll("#note-stage-checklist input[type='checkbox']")];
    const taskId = Number(document.getElementById("note-task-select").value);
    const task = noteWorkflowData?.tasks.find(item => item.id === taskId);
    const stage = noteStageNames.includes(task?.noteStage) ? task.noteStage : "企画";
    const hasNextStage = noteStageNames.indexOf(stage) < noteStageNames.length - 1;
    document.getElementById("note-stage-next").disabled = !hasNextStage || checks.some(check => !check.checked);
});

document.getElementById("note-task-select").addEventListener("change", () => {
    document.getElementById("note-stage-message").textContent = "";
    renderNoteWorkflow();
});
async function saveNoteStage(taskId, stage, successText) {
    const saveButton = document.getElementById("note-stage-save");
    const nextButton = document.getElementById("note-stage-next");
    const message = document.getElementById("note-stage-message");
    saveButton.disabled = true;
    nextButton.disabled = true;
    message.textContent = "保存中...";
    try {
        const response = await fetch(`/api/tasks/${taskId}/note-stage`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ noteStage: stage })
        });
        if (!response.ok) throw new Error(await response.text());
        const task = noteWorkflowData.tasks.find(item => item.id === taskId);
        if (task) task.noteStage = stage;
        renderNoteWorkflow();
        message.textContent = successText;
        window.setTimeout(() => {
            if (message.textContent === successText) message.textContent = "";
        }, 5000);
    } catch (error) {
        message.textContent = "保存できませんでした。もう一度試してください。";
        console.error(error);
    } finally {
        saveButton.disabled = false;
        renderNoteWorkflow();
    }
}

document.getElementById("note-stage-save").addEventListener("click", () => {
    const taskId = Number(document.getElementById("note-task-select").value);
    const stage = document.getElementById("note-stage-select").value;
    saveNoteStage(taskId, stage, "進行段階を修正しました。");
});

document.getElementById("note-stage-next").addEventListener("click", () => {
    const taskId = Number(document.getElementById("note-task-select").value);
    const task = noteWorkflowData?.tasks.find(item => item.id === taskId);
    if (!task) return;
    const stage = noteStageNames.includes(task.noteStage) ? task.noteStage : "企画";
    const nextStage = noteStageNames[noteStageNames.indexOf(stage) + 1];
    if (!nextStage) return;
    const confirmed = window.confirm(`「${stage}」の作業は完了しましたか？\nOKを押すと「${nextStage}」へ進みます。\n公開・SNS投稿・売上登録は自動では行いません。`);
    if (!confirmed) return;
    saveNoteStage(taskId, nextStage, `「${stage}」を完了し、「${nextStage}」へ進みました。`);
});

const WRITER_DRAFT_KEY = "senninsyou-writer-draft";
const writerOutputIds = {
    title: "writer-output-title",
    freeSection: "writer-output-free",
    paidSection: "writer-output-paid",
    salesDescription: "writer-output-sales",
    snsPost: "writer-output-sns",
    reviewNotes: "writer-output-review"
};
const marketingOutputIds = {
    titleIdeas: "writer-output-titles",
    priceAdvice: "writer-output-price",
    promotionPlan: "writer-output-plan",
    metrics: "writer-output-metrics"
};
const researchOutputIds = {
    demand: "writer-output-demand",
    angle: "writer-output-angle"
};
const writerInputIds = {
    theme: "writer-theme",
    audience: "writer-audience",
    price: "writer-price",
    sourceNotes: "writer-source-notes"
};
const writerSavedIds = { ...writerInputIds, ...writerOutputIds, ...marketingOutputIds, ...researchOutputIds };

const writerInterviewQuestions = document.getElementById("writer-interview-questions");

function renderInterviewQuestions(questions) {
    writerInterviewQuestions.replaceChildren();
    questions.forEach(question => {
        const label = document.createElement("label");
        label.textContent = question;
        const answer = document.createElement("textarea");
        answer.rows = 3;
        answer.maxLength = 1000;
        answer.dataset.interviewQuestion = question;
        answer.addEventListener("input", saveWriterDraft);
        label.appendChild(answer);
        writerInterviewQuestions.appendChild(label);
    });
    writerInterviewQuestions.hidden = questions.length === 0;
}

function collectInterviewAnswers() {
    return [...writerInterviewQuestions.querySelectorAll("textarea")]
        .map(field => ({ question: field.dataset.interviewQuestion, answer: field.value }));
}

function buildInterviewNotes() {
    return collectInterviewAnswers()
        .filter(item => item.answer.trim())
        .map(item => `質問：${item.question}\n回答：${item.answer.trim()}`)
        .join("\n\n");
}

function saveWriterDraft() {
    const draft = { savedAt: new Date().toISOString(), interview: collectInterviewAnswers() };
    for (const [field, id] of Object.entries(writerSavedIds)) {
        draft[field] = document.getElementById(id).value;
    }
    try {
        localStorage.setItem(WRITER_DRAFT_KEY, JSON.stringify(draft));
    } catch (error) {
        console.error(error);
    }
}

function restoreWriterDraft() {
    let draft = null;
    try {
        draft = JSON.parse(localStorage.getItem(WRITER_DRAFT_KEY) ?? "null");
    } catch (error) {
        return;
    }
    if (!draft) return;
    if (Array.isArray(draft.interview) && draft.interview.length > 0) {
        renderInterviewQuestions(draft.interview.map(item => item.question));
        [...writerInterviewQuestions.querySelectorAll("textarea")].forEach((field, index) => {
            field.value = draft.interview[index].answer ?? "";
        });
        document.getElementById("writer-interview-remember").hidden = false;
    }
    for (const [field, id] of Object.entries(writerSavedIds)) {
        document.getElementById(id).value = draft[field] ?? "";
    }
    const hasSection = ids => Object.keys(ids).some(field => (draft[field] ?? "").trim());
    document.getElementById("writer-result").hidden = !hasSection(writerOutputIds);
    document.getElementById("writer-marketing-result").hidden = !hasSection(marketingOutputIds);
    document.getElementById("writer-research-result").hidden = !hasSection(researchOutputIds);
    if (hasSection(writerOutputIds)) {
        document.getElementById("writer-status").textContent =
            `前回の下書きを表示しています（${new Date(draft.savedAt).toLocaleString("ja-JP")}）。`;
    }
}

Object.values(writerSavedIds).forEach(id => {
    document.getElementById(id).addEventListener("input", saveWriterDraft);
});
restoreWriterDraft();

const authorFactsList = document.getElementById("author-facts-list");

async function loadAuthorFacts() {
    try {
        const response = await fetch("/api/author-facts");
        if (!response.ok) throw new Error(await response.text());
        renderAuthorFacts(await response.json());
    } catch (error) {
        console.error(error);
    }
}

function renderAuthorFacts(facts) {
    authorFactsList.replaceChildren();
    if (facts.length === 0) {
        const empty = document.createElement("p");
        empty.className = "author-facts-empty";
        empty.textContent = "まだ何も記憶していません。取材に答えると、ここに貯まります。";
        authorFactsList.appendChild(empty);
        return;
    }
    facts.forEach(item => {
        const row = document.createElement("div");
        row.className = "author-fact";

        const badge = document.createElement("span");
        badge.className = "author-fact-category";
        badge.textContent = item.category;

        const body = document.createElement("div");
        const topic = document.createElement("strong");
        topic.textContent = item.topic;
        const fact = document.createElement("p");
        fact.textContent = item.fact;
        body.append(topic, fact);

        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "secondary-button";
        remove.textContent = "削除";
        remove.addEventListener("click", async () => {
            if (!window.confirm("この記憶を削除しますか？")) return;
            await fetch(`/api/author-facts/${item.id}`, { method: "DELETE" });
            loadAuthorFacts();
        });

        row.append(badge, body, remove);
        authorFactsList.appendChild(row);
    });
}

async function saveAuthorFact(category, topic, fact) {
    const response = await fetch("/api/author-facts", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ category, topic, fact })
    });
    if (!response.ok) throw new Error(await response.text());
}

document.getElementById("author-fact-add").addEventListener("click", async () => {
    const status = document.getElementById("author-facts-status");
    const topic = document.getElementById("author-fact-topic");
    const fact = document.getElementById("author-fact-text");
    if (!topic.value.trim() || !fact.value.trim()) {
        status.textContent = "項目と内容を入力してください。";
        return;
    }
    try {
        await saveAuthorFact(
            document.getElementById("author-fact-category").value,
            topic.value.trim(),
            fact.value.trim());
        topic.value = "";
        fact.value = "";
        status.textContent = "記憶に追加しました。";
        loadAuthorFacts();
    } catch (error) {
        status.textContent = "記憶に追加できませんでした。";
        console.error(error);
    }
});

document.getElementById("writer-interview-remember").addEventListener("click", async () => {
    const button = document.getElementById("writer-interview-remember");
    const status = document.getElementById("writer-interview-status");
    const answered = collectInterviewAnswers().filter(item => item.answer.trim());
    if (answered.length === 0) {
        status.textContent = "記憶できる回答がありません。";
        return;
    }
    button.disabled = true;
    try {
        for (const item of answered) {
            await saveAuthorFact("その他", item.question.slice(0, 255), item.answer.trim());
        }
        status.textContent = `${answered.length}件を記憶しました。次からは同じことを聞かれません。`;
        loadAuthorFacts();
    } catch (error) {
        status.textContent = "記憶できませんでした。";
        console.error(error);
    } finally {
        button.disabled = false;
    }
});

loadAuthorFacts();

const WRITER_JOB_TIMEOUT_MS = 5 * 60 * 1000;
let writerJobTimer = null;

function setWriterBusy(busy) {
    const button = document.getElementById("writer-generate");
    button.disabled = busy;
    button.textContent = busy ? "ライターAIが執筆中..." : "記事の下書きを作る";
}

function applyWriterJobResult(job) {
    for (const [field, id] of Object.entries(writerOutputIds)) {
        document.getElementById(id).value = job[field] ?? "";
    }
    document.getElementById("writer-result").hidden = false;
    saveWriterDraft();
}

// 執筆はサーバー側で動くので、画面を閉じても進む。開き直したら状態を見に行く。
function watchWriterJob(jobId, startedAt) {
    const status = document.getElementById("writer-status");
    clearInterval(writerJobTimer);
    setWriterBusy(true);

    const check = async () => {
        const seconds = Math.floor((Date.now() - startedAt) / 1000);
        try {
            const response = await fetch(`/api/ai/writer/jobs/${jobId}`);
            if (!response.ok) throw new Error(await response.text());
            const job = await response.json();

            if (job.status === "完了") {
                clearInterval(writerJobTimer);
                applyWriterJobResult(job);
                setWriterBusy(false);
                status.textContent = "下書きを作成しました。内容を確認して修正してください。";
                document.getElementById("writer-result")
                    .scrollIntoView({ behavior: "smooth", block: "start" });
                return;
            }
            if (job.status === "失敗") {
                clearInterval(writerJobTimer);
                setWriterBusy(false);
                const messages = {
                    "WAI-CONFIG": "AI設定を確認する必要があります。",
                    "WAI-API": "生成APIとの通信で失敗しました。",
                    "WAI-EMPTY": "生成APIから文章が返りませんでした。",
                    "WAI-FORMAT": "文章は返りましたが、項目の分割に失敗しました。",
                    "WAI-DB": "依頼をデータベースへ保存できませんでした。"
                };
                status.textContent =
                    `${messages[job.errorCode] ?? "下書きを作成できませんでした。"} 診断コード: ${job.errorCode}`;
                return;
            }
            if (Date.now() - startedAt > WRITER_JOB_TIMEOUT_MS) {
                clearInterval(writerJobTimer);
                setWriterBusy(false);
                status.textContent =
                    "執筆が終わりません。サーバーが再起動した可能性があります。もう一度お試しください。";
                return;
            }
            status.textContent =
                `執筆中です。${seconds}秒経過（1〜2分かかります）。この画面を閉じても執筆は続きます。`;
        } catch (error) {
            console.error(error);
        }
    };

    check();
    writerJobTimer = setInterval(check, 3000);
}

async function resumeWriterJob() {
    try {
        const response = await fetch("/api/ai/writer/jobs/latest");
        if (!response.ok) return;
        const job = await response.json();
        if (!job.id) return;

        if (job.status === "受付" || job.status === "執筆中") {
            watchWriterJob(job.id, Date.now());
            return;
        }
        if (job.status === "完了" && !document.getElementById("writer-output-title").value.trim()) {
            applyWriterJobResult(job);
            document.getElementById("writer-status").textContent =
                "前回サーバーで作成した下書きを表示しています。";
        }
    } catch (error) {
        console.error(error);
    }
}

resumeWriterJob();

document.getElementById("writer-research-start").addEventListener("click", async () => {
    const button = document.getElementById("writer-research-start");
    const status = document.getElementById("writer-research-status");
    const sourceNotes = document.getElementById("writer-source-notes");
    const payload = {
        theme: document.getElementById("writer-theme").value.trim(),
        audience: document.getElementById("writer-audience").value.trim(),
        sourceNotes: sourceNotes.value.trim()
    };
    if (!payload.theme || !payload.audience) {
        status.textContent = "テーマと想定読者を入力してください。";
        return;
    }
    button.disabled = true;
    button.textContent = "軍師AIが分析中...";
    const startedAt = Date.now();
    const showElapsed = () => {
        const seconds = Math.floor((Date.now() - startedAt) / 1000);
        status.textContent = `需要と切り口を分析しています。${seconds}秒経過。`;
    };
    showElapsed();
    const elapsedTimer = setInterval(showElapsed, 1000);
    try {
        const response = await fetch("/api/ai/writer/research", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        if (!response.ok) throw new Error(await response.text());
        const result = await response.json();
        if (result.errorCode) {
            throw new Error(`材料を用意できませんでした。診断コード: ${result.errorCode}`);
        }
        for (const [field, id] of Object.entries(researchOutputIds)) {
            document.getElementById(id).value = result[field] ?? "";
        }
        document.getElementById("writer-research-result").hidden = false;
        const material = result.material ?? "";
        if (!sourceNotes.value.trim()
            || window.confirm("「伝えたい内容」をAIの下書きで置き換えますか？\nキャンセルすると、今の内容はそのまま残ります。")) {
            sourceNotes.value = material;
        }
        saveWriterDraft();
        status.textContent = "材料を用意しました。空欄の項目をあなたの事実で埋めてください。";
    } catch (error) {
        status.textContent = error instanceof Error ? error.message : "材料を用意できませんでした。";
        console.error(error);
    } finally {
        clearInterval(elapsedTimer);
        button.disabled = false;
        button.textContent = "AIに材料を用意してもらう";
    }
});

document.getElementById("writer-marketing-start").addEventListener("click", async () => {
    const button = document.getElementById("writer-marketing-start");
    const status = document.getElementById("writer-marketing-status");
    const payload = {
        theme: document.getElementById("writer-theme").value.trim(),
        audience: document.getElementById("writer-audience").value.trim(),
        price: document.getElementById("writer-price").value.trim(),
        title: document.getElementById("writer-output-title").value.trim(),
        freeSection: document.getElementById("writer-output-free").value.trim(),
        paidSection: document.getElementById("writer-output-paid").value.trim()
    };
    if (!payload.title || !payload.freeSection) {
        status.textContent = "先に記事の下書きを作ってください。";
        return;
    }
    if (!payload.theme || !payload.audience) {
        status.textContent = "テーマと想定読者を入力してください。";
        return;
    }
    button.disabled = true;
    button.textContent = "営業AIが作成中...";
    const startedAt = Date.now();
    const showElapsed = () => {
        const seconds = Math.floor((Date.now() - startedAt) / 1000);
        status.textContent = `販売戦略を作成しています。${seconds}秒経過。`;
    };
    showElapsed();
    const elapsedTimer = setInterval(showElapsed, 1000);
    try {
        const response = await fetch("/api/ai/writer/marketing", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        if (!response.ok) throw new Error(await response.text());
        const result = await response.json();
        if (result.errorCode) {
            throw new Error(`販売戦略を作れませんでした。診断コード: ${result.errorCode}`);
        }
        for (const [field, id] of Object.entries(marketingOutputIds)) {
            document.getElementById(id).value = result[field] ?? "";
        }
        document.getElementById("writer-marketing-result").hidden = false;
        saveWriterDraft();
        status.textContent = "販売戦略を作成しました。実行するかどうかはご自身で判断してください。";
    } catch (error) {
        status.textContent = error instanceof Error ? error.message : "販売戦略を作れませんでした。";
        console.error(error);
    } finally {
        clearInterval(elapsedTimer);
        button.disabled = false;
        button.textContent = "販売戦略を作る";
    }
});

document.getElementById("writer-interview-start").addEventListener("click", async () => {
    const button = document.getElementById("writer-interview-start");
    const status = document.getElementById("writer-interview-status");
    const payload = {
        theme: document.getElementById("writer-theme").value.trim(),
        audience: document.getElementById("writer-audience").value.trim(),
        sourceNotes: document.getElementById("writer-source-notes").value.trim()
    };
    if (!payload.theme || !payload.audience) {
        status.textContent = "テーマと想定読者を入力してください。";
        return;
    }
    if (writerInterviewQuestions.querySelector("textarea")
        && !window.confirm("質問を作り直すと、今の回答は消えます。続けますか？")) {
        return;
    }
    button.disabled = true;
    button.textContent = "質問を考えています...";
    status.textContent = "AIが質問を考えています。";
    try {
        const response = await fetch("/api/ai/writer/interview", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        if (!response.ok) throw new Error(await response.text());
        const result = await response.json();
        if (result.errorCode) {
            throw new Error(`質問を作れませんでした。診断コード: ${result.errorCode}`);
        }
        const questions = result.questions ?? [];
        renderInterviewQuestions(questions);
        document.getElementById("writer-interview-remember").hidden = questions.length === 0;
        saveWriterDraft();
        status.textContent = questions.length === 0
            ? "記憶している事実だけで書けるため、今回は質問がありません。"
            : "答えられる範囲で入力してください。空欄のままでも下書きは作れます。";
    } catch (error) {
        status.textContent = error instanceof Error ? error.message : "質問を作れませんでした。";
        console.error(error);
    } finally {
        button.disabled = false;
        button.textContent = "AIに質問してもらう";
    }
});

document.getElementById("writer-generate").addEventListener("click", async () => {
    const button = document.getElementById("writer-generate");
    const status = document.getElementById("writer-status");
    const payload = {
        theme: document.getElementById("writer-theme").value.trim(),
        audience: document.getElementById("writer-audience").value.trim(),
        price: document.getElementById("writer-price").value.trim(),
        sourceNotes: document.getElementById("writer-source-notes").value.trim(),
        interviewNotes: buildInterviewNotes()
    };
    if (!payload.theme || !payload.audience || !payload.sourceNotes) {
        status.textContent = "テーマ・想定読者・伝えたい内容を入力してください。";
        return;
    }
    button.disabled = true;
    button.textContent = "ライターAIが執筆中...";
    status.textContent = "依頼を送っています。";
    try {
        const response = await fetch("/api/ai/writer/jobs", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        if (!response.ok) throw new Error(await response.text());
        const job = await response.json();
        if (job.errorCode) {
            throw new Error(`執筆を開始できませんでした。診断コード: ${job.errorCode}`);
        }
        watchWriterJob(job.id, Date.now());
    } catch (error) {
        status.textContent = error instanceof Error ? error.message : "執筆を開始できませんでした。";
        console.error(error);
        button.disabled = false;
        button.textContent = "記事の下書きを作る";
    }
});

document.getElementById("writer-copy").addEventListener("click", async () => {
    const fullArticle = [
        document.getElementById("writer-output-title").value,
        document.getElementById("writer-output-free").value,
        document.getElementById("writer-output-paid").value,
        "販売ページの説明",
        document.getElementById("writer-output-sales").value,
        "SNS投稿案",
        document.getElementById("writer-output-sns").value,
        "公開前の確認事項",
        document.getElementById("writer-output-review").value
    ].join("\n\n");
    const status = document.getElementById("writer-status");
    try {
        await navigator.clipboard.writeText(fullArticle);
        status.textContent = "記事と関連文章をコピーしました。";
    } catch (error) {
        status.textContent = "コピーできませんでした。文章を選択してコピーしてください。";
        console.error(error);
    }
});

document.getElementById("writer-quality-check").addEventListener("click", async () => {
    const button = document.getElementById("writer-quality-check");
    const status = document.getElementById("writer-status");
    const payload = {
        theme: document.getElementById("writer-theme").value.trim(),
        audience: document.getElementById("writer-audience").value.trim(),
        price: document.getElementById("writer-price").value.trim(),
        title: document.getElementById("writer-output-title").value.trim(),
        freeSection: document.getElementById("writer-output-free").value.trim(),
        paidSection: document.getElementById("writer-output-paid").value.trim(),
        salesDescription: document.getElementById("writer-output-sales").value.trim()
    };
    if (Object.entries(payload).some(([key, value]) => key !== "price" && !value)) {
        status.textContent = "記事の必須項目を入力してから品質を確認してください。";
        return;
    }
    button.disabled = true;
    button.textContent = "品質管理AIが確認中...";
    status.textContent = "差別化・購入価値・信頼性を確認しています。";
    try {
        const response = await fetch("/api/ai/writer/quality", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        if (!response.ok) throw new Error(await response.text());
        const result = await response.json();
        if (result.errorCode) throw new Error(`品質を判定できませんでした。診断コード: ${result.errorCode}`);
        document.getElementById("writer-quality-score").textContent = `${result.score}点 / 100点`;
        document.getElementById("writer-quality-verdict").textContent = `判定：${result.verdict ?? ""}`;
        document.getElementById("writer-quality-strengths").textContent = result.strengths ?? "";
        document.getElementById("writer-quality-improvements").textContent = result.improvements ?? "";
        document.getElementById("writer-quality-questions").textContent = result.questions ?? "";
        document.getElementById("writer-quality-result").hidden = false;
        status.textContent = "品質判定が完了しました。改善後にもう一度確認できます。";
        document.getElementById("writer-quality-result").scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (error) {
        status.textContent = error instanceof Error ? error.message : "品質を判定できませんでした。";
        console.error(error);
    } finally {
        button.disabled = false;
        button.textContent = "販売前の品質をチェック";
    }
});

function renderAiResponse(container, data) {
    const proposal = document.createElement("div");
    proposal.className = "ai-result";

    const heading = document.createElement("div");
    heading.className = "ai-result-heading";
    const title = document.createElement("h3");
    title.textContent = "⚔️ 登録前のタスク案";
    const badge = document.createElement("span");
    badge.textContent = "未登録";
    heading.append(title, badge);

    proposal.appendChild(heading);

    const hasNextTask = typeof data.nextTask === "string" && data.nextTask.trim() !== "";
    if (!hasNextTask) {
        const empty = document.createElement("p");
        empty.textContent = "まだタスク案はまとまっていません。続きを相談してから再度お試しください。";
        proposal.appendChild(empty);
        container.appendChild(proposal);
        return null;
    }

    const mission = document.createElement("div");
    mission.className = "ai-next-mission";
    const missionLabel = document.createElement("span");
    missionLabel.textContent = "⚔️ 次のタスク";
    const missionText = document.createElement("p");
    missionText.textContent = data.nextTask;
    mission.append(missionLabel, missionText);

    const footer = document.createElement("div");
    footer.className = "ai-result-footer";
    const meta = document.createElement("div");
    meta.className = "ai-result-meta";
    const priority = document.createElement("span");
    priority.className = `priority-badge priority-${priorityClass(data.priority)}`;
    priority.textContent = `優先度 ${data.priority}`;
    const agent = document.createElement("span");
    agent.className = "agent-badge";
    agent.textContent = `🤖 ${data.assignedAgent}`;
    meta.append(priority, agent);

    const approveButton = document.createElement("button");
    approveButton.id = "approve-task";
    approveButton.type = "button";
    approveButton.textContent = "⚔️ このタスクを登録";
    footer.append(meta, approveButton);
    proposal.append(mission, footer);
    container.appendChild(proposal);
    return approveButton;
}

function createInsightCard(icon, title, content, variant) {
    const card = document.createElement("article");
    card.className = `ai-insight-card ${variant}`;
    const heading = document.createElement("h4");
    heading.textContent = `${icon} ${title}`;
    const text = document.createElement("p");
    text.textContent = content ?? "記録がありません。";
    card.append(heading, text);
    return card;
}

function renderEmptyState(container, icon, title, description) {
    container.replaceChildren();
    const empty = document.createElement("div");
    empty.className = "empty-state";
    const iconElement = document.createElement("span");
    iconElement.textContent = icon;
    const content = document.createElement("div");
    const heading = document.createElement("strong");
    heading.textContent = title;
    const text = document.createElement("p");
    text.textContent = description;
    content.append(heading, text);
    empty.append(iconElement, content);
    container.appendChild(empty);
}

navButtons.forEach(button => {
    button.addEventListener("click", () => showPage(button.dataset.page));
});

document.querySelectorAll("[data-open-page]").forEach(button => {
    button.addEventListener("click", () => showPage(button.dataset.openPage));
});

document.querySelectorAll(".quick-command").forEach(button => {
    button.addEventListener("click", () => {
        commandInput.value = button.dataset.command;
        commandInput.focus();
    });
});

const numberOptionSets = {
    amount: [
        ["1000", "1,000円"],
        ["3000", "3,000円"],
        ["5000", "5,000円"],
        ["10000", "10,000円"],
        ["30000", "30,000円"],
        ["50000", "50,000円"],
        ["100000", "100,000円"]
    ],
    time: [
        ["30", "30分"],
        ["60", "1時間"],
        ["90", "1時間30分"],
        ["120", "2時間"],
        ["180", "3時間"],
        ["240", "4時間"],
        ["480", "8時間"]
    ]
};

document.querySelectorAll("[data-number-options]").forEach(input => {
    const wrapper = document.createElement("div");
    wrapper.className = "number-picker";
    input.before(wrapper);
    wrapper.appendChild(input);

    const toggle = document.createElement("button");
    toggle.className = "number-picker-toggle";
    toggle.type = "button";
    toggle.textContent = "▼";
    toggle.setAttribute("aria-label", "候補を表示");
    toggle.setAttribute("aria-expanded", "false");

    const menu = document.createElement("div");
    menu.className = "number-picker-menu";
    menu.hidden = true;

    numberOptionSets[input.dataset.numberOptions].forEach(([value, label]) => {
        const option = document.createElement("button");
        option.type = "button";
        option.textContent = label;
        option.addEventListener("click", () => {
            input.value = value;
            menu.hidden = true;
            toggle.setAttribute("aria-expanded", "false");
            input.focus();
        });
        menu.appendChild(option);
    });

    toggle.addEventListener("click", () => {
        const willOpen = menu.hidden;
        document.querySelectorAll(".number-picker-menu").forEach(otherMenu => {
            otherMenu.hidden = true;
        });
        document.querySelectorAll(".number-picker-toggle").forEach(otherToggle => {
            otherToggle.setAttribute("aria-expanded", "false");
        });
        menu.hidden = !willOpen;
        toggle.setAttribute("aria-expanded", String(willOpen));
    });

    wrapper.append(toggle, menu);
});

document.addEventListener("click", event => {
    if (!event.target.closest(".number-picker")) {
        document.querySelectorAll(".number-picker-menu").forEach(menu => {
            menu.hidden = true;
        });
        document.querySelectorAll(".number-picker-toggle").forEach(toggle => {
            toggle.setAttribute("aria-expanded", "false");
        });
    }
});

revenueDate.valueAsDate = new Date();

commandButton.addEventListener("click", () => sendCommand());
document.getElementById("propose-task-button").addEventListener("click", () => sendCommand("propose"));
document.getElementById("continue-conversation-button").addEventListener("click", () => {
    commandInput.scrollIntoView({ behavior: "smooth", block: "center" });
    commandInput.focus({ preventScroll: true });
});

commandInput.addEventListener("keydown", event => {
    if (event.key === "Enter") {
        sendCommand();
    }
});

document.getElementById("video-plan-button").addEventListener("click", () => {
    const topic = document.getElementById("video-topic").value.trim();
    const notes = document.getElementById("video-source-notes").value.trim();
    const platform = document.getElementById("video-platform").value;
    if (!topic) {
        document.getElementById("video-topic").focus();
        return;
    }
    const prompt = `制作AIとして、${platform}向け短尺動画の編集案を相談したい。テーマ：${topic}。素材・要望：${notes || "未指定"}。冒頭のフック、構成、短いナレーション案、画面に出す文字、編集手順、公開前の確認項目を具体的に示して。素材を実際に見ていないこと、需要や効果は未検証であることを明記して。動画の自動編集や投稿、タスク登録はしないで。`;
    if (prompt.length > 2000) {
        alert("入力が長すぎます。素材・要望を短くしてください。");
        return;
    }
    commandInput.value = prompt;
    showPage("command");
    sendCommand();
});

let videoPreviewUrl = null;
document.getElementById("video-file").addEventListener("change", event => {
    const file = event.target.files[0];
    const result = document.getElementById("video-check-result");
    if (videoPreviewUrl) {
        URL.revokeObjectURL(videoPreviewUrl);
        videoPreviewUrl = null;
    }
    result.replaceChildren();
    if (!file) return;
    result.textContent = "動画情報を確認中...";
    videoPreviewUrl = URL.createObjectURL(file);
    const preview = document.createElement("video");
    preview.preload = "metadata";
    preview.src = videoPreviewUrl;
    preview.onloadedmetadata = () => {
        const seconds = Math.round(preview.duration);
        const portrait = preview.videoHeight > preview.videoWidth;
        const ratio = preview.videoWidth / preview.videoHeight;
        const closeToNineSixteen = portrait && Math.abs(ratio - 9 / 16) < 0.03;
        result.replaceChildren();
        const lines = [
            `ファイル：${file.name}（${(file.size / 1024 / 1024).toFixed(1)} MB）`,
            `画面：${preview.videoWidth} × ${preview.videoHeight} — ${closeToNineSixteen ? "縦型9:16に近い" : "縦型9:16ではない可能性があります"}`,
            `長さ：${seconds}秒 — ${seconds > 0 && seconds <= 60 ? "短尺" : "公開先の長さ条件を確認してください"}`,
            "確認待ち：音声・字幕・事実・素材の権利・最後の呼びかけ"
        ];
        lines.forEach(line => {
            const paragraph = document.createElement("p");
            paragraph.textContent = line;
            result.appendChild(paragraph);
        });
        preview.removeAttribute("src");
        preview.load();
    };
    preview.onerror = () => {
        result.textContent = "この動画形式の情報をブラウザで読み取れませんでした。PCの動画編集ソフトで確認してください。";
    };
});

revenueForm.addEventListener("submit", async event => {
    event.preventDefault();

    const saveButton = document.getElementById("save-revenue");
    const message = document.getElementById("revenue-message");
    const taskValue = document.getElementById("revenue-task").value;

    const payload = {
        taskId: taskValue === "" ? null : Number(taskValue),
        description: document.getElementById("revenue-description").value.trim(),
        revenue: Number(document.getElementById("revenue-amount").value || 0),
        expense: Number(document.getElementById("expense-amount").value || 0),
        workMinutes: Number(document.getElementById("work-minutes").value || 0),
        occurredOn: revenueDate.value,
        notes: document.getElementById("revenue-notes").value.trim()
    };

    saveButton.disabled = true;
    message.textContent = "記録中...";

    try {
        const response = await fetch("/api/revenue", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            throw new Error(await response.text());
        }

        revenueForm.reset();
        revenueDate.valueAsDate = new Date();
        document.getElementById("revenue-amount").value = "";
        document.getElementById("expense-amount").value = "";
        document.getElementById("work-minutes").value = "";
        message.textContent = "✅ 実績を記録しました。";
        await Promise.all([
            loadRevenueDashboard(),
            loadOpportunities()
        ]);
    } catch (error) {
        message.textContent = "収益記録の登録に失敗しました。";
        console.error(error);
    } finally {
        saveButton.disabled = false;
    }
});

opportunityForm.addEventListener("submit", async event => {
    event.preventDefault();
    const button = document.getElementById("save-opportunity");
    const message = document.getElementById("opportunity-message");
    const payload = {
        title: document.getElementById("opportunity-title").value.trim(),
        opportunityType: document.getElementById("opportunity-type").value,
        expectedRevenue: Number(document.getElementById("opportunity-revenue").value || 0),
        estimatedMinutes: Number(document.getElementById("opportunity-minutes").value || 0),
        riskLevel: document.getElementById("opportunity-risk").value,
        notes: document.getElementById("opportunity-notes").value.trim()
    };

    button.disabled = true;
    message.textContent = "登録中...";
    try {
        const response = await fetch("/api/opportunities", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        if (!response.ok) {
            throw new Error(await response.text());
        }
        opportunityForm.reset();
        document.getElementById("opportunity-revenue").value = "";
        document.getElementById("opportunity-minutes").value = "";
        message.textContent = "✅ 収益機会を登録しました。";
        await loadOpportunities();
    } catch (error) {
        message.textContent = "収益機会の登録に失敗しました。";
        console.error(error);
    } finally {
        button.disabled = false;
    }
});
