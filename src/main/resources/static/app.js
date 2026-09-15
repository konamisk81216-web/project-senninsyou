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
            renderEmptyState(taskList, "⚔️", "任務はありません", "AI将軍の提案から最初の任務を登録できます。");
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
                        alert("任務状態の更新に失敗しました。");
                        console.error(error);
                        statusButton.disabled = false;
                    }
                });

                actions.appendChild(statusButton);
            }

            const deleteButton = document.createElement("button");
            deleteButton.className = "delete-button";
            deleteButton.textContent = "🗑 任務を削除";

            deleteButton.addEventListener("click", async () => {
                const shouldDelete = window.confirm(
                    `任務「${task.taskName}」を削除しますか？\nこの操作は元に戻せません。`
                );

                if (!shouldDelete) {
                    return;
                }

                deleteButton.disabled = true;

                try {
                    await deleteTask(task.id);
                    await loadTasks();
                } catch (error) {
                    alert("任務の削除に失敗しました。");
                    console.error(error);
                    deleteButton.disabled = false;
                }
            });

            actions.appendChild(deleteButton);
            taskCard.appendChild(actions);
            taskList.appendChild(taskCard);
        });

    } catch (error) {
        taskList.innerHTML = "<p>任務の取得に失敗しました。</p>";
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
        container.textContent = "現在、着手すべき任務はありません。";
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
    emptyOption.textContent = "任務と関連付けない";
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
            traceTitle.textContent = "🔗 任務・戦果トレーサビリティ";
            const taskState = document.createElement("p");
            taskState.textContent = `関連任務：${opportunity.linkedTaskName}（${opportunity.linkedTaskStatus}）`;

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
            taskButton.textContent = "⚔ 任務として登録";
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
    if (!window.confirm(`「${opportunity.title}」を任務として登録しますか？`)) {
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
        button.textContent = "✅ 任務登録済み";
        await Promise.all([loadTasks(), loadOpportunities()]);
    } catch (error) {
        alert("任務の登録に失敗しました。");
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
        renderEmptyState(container, "📊", "分析データがありません", "任務に関連付けた戦果を記録すると、効率とROIを比較できます。");
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
        count.textContent = `${analysis.recordCount}件の戦果`;
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
        renderEmptyState(revenueList, "💰", "戦果はまだありません", "最初の売上・経費・作業時間を記録しましょう。");
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

    async function sendCommand(mode = "discuss") {
    const input = document.getElementById("command-input");
    const command = mode === "propose"
        ? "これまで話した内容を踏まえ、登録前に確認する任務案を1件まとめて。"
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
    commandButton.textContent = "考え中...";
    status.textContent = mode === "propose" ? "任務案を整理しています..." : "AI将軍が考えています...";
    status.hidden = false;
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
                        throw new Error("任務登録に失敗しました。");
                    }

                    approveButton.disabled = true;
                    approveButton.textContent = "✅ 登録済み";

                    await loadTasks();

                } catch (error) {
                    alert("任務の登録に失敗しました。");
                    console.error(error);
                }
            });
        }

        if (mode !== "propose") input.value = "";

    } catch (error) {
        alert("AI将軍への命令送信に失敗しました。");
        console.error(error);
    } finally {
        commandButton.disabled = false;
        proposalButton.disabled = false;
        commandButton.textContent = "将軍と話す";
        status.hidden = true;
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
            createInsightCard("💰", "収益判断", data.revenueInsight, "revenue"),
            createInsightCard("📊", "戦果評価", data.performanceDecision, "analysis"),
            createInsightCard("🚀", "市場機会", data.marketOpportunity, "market"));
        details.append(summary, grid);
        entry.appendChild(details);
    }
    container.appendChild(entry);
}

const commandButton = document.getElementById("command-button");
const commandInput = document.getElementById("command-input");
const revenueForm = document.getElementById("revenue-form");
const revenueDate = document.getElementById("revenue-date");
const opportunityForm = document.getElementById("opportunity-form");
const pageTitle = document.getElementById("page-title");
const navButtons = document.querySelectorAll(".nav-button");
const pagePanels = document.querySelectorAll("[data-page-panel]");

const pageTitles = {
    command: "司令本部",
    tasks: "任務管理",
    opportunities: "機会発見レーダー",
    revenue: "収益・戦果分析",
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

    pageTitle.textContent = pageTitles[page] ?? "司令本部";
    window.scrollTo({ top: 0, behavior: "smooth" });
}

function renderAiResponse(container, data) {
    const proposal = document.createElement("div");
    proposal.className = "ai-result";

    const heading = document.createElement("div");
    heading.className = "ai-result-heading";
    const title = document.createElement("h3");
    title.textContent = "⚔️ 登録前の任務案";
    const badge = document.createElement("span");
    badge.textContent = "未登録";
    heading.append(title, badge);

    proposal.appendChild(heading);

    const hasNextTask = typeof data.nextTask === "string" && data.nextTask.trim() !== "";
    if (!hasNextTask) {
        const empty = document.createElement("p");
        empty.textContent = "まだ任務案はまとまっていません。続きを相談してから再度お試しください。";
        proposal.appendChild(empty);
        container.appendChild(proposal);
        return null;
    }

    const mission = document.createElement("div");
    mission.className = "ai-next-mission";
    const missionLabel = document.createElement("span");
    missionLabel.textContent = "⚔️ 次の任務";
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
    approveButton.textContent = "⚔️ この任務を登録";
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
    const prompt = `制作AIとして、${platform}向け短尺動画の編集案を相談したい。テーマ：${topic}。素材・要望：${notes || "未指定"}。冒頭のフック、構成、短いナレーション案、画面に出す文字、編集手順、公開前の確認項目を具体的に示して。素材を実際に見ていないこと、需要や効果は未検証であることを明記して。動画の自動編集や投稿、任務登録はしないで。`;
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
        message.textContent = "✅ 戦果を記録しました。";
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
