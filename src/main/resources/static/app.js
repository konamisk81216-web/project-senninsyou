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

    async function sendCommand() {
    const input = document.getElementById("command-input");
    const command = input.value.trim();

    if (command === "") {
        alert("将軍への命令を入力してください。");
        return;
    }

    try {
        const response = await fetch("/api/ai/command", {
            method: "POST",
            headers: {
                "Content-Type": "text/plain"
            },
            body: command
        });

        const result = await response.text();
        const data = JSON.parse(result);

        const aiResponse = document.getElementById("ai-response");
        const approveButton = renderAiResponse(aiResponse, data);

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

        input.value = "";

    } catch (error) {
        alert("AI将軍への命令送信に失敗しました。");
        console.error(error);
    }
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
    revenue: "収益・戦果分析"
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
    container.replaceChildren();
    container.className = "ai-result";

    const heading = document.createElement("div");
    heading.className = "ai-result-heading";
    const title = document.createElement("h3");
    title.textContent = "👑 AI将軍の回答";
    const badge = document.createElement("span");
    badge.textContent = "戦略提案";
    heading.append(title, badge);

    const insightGrid = document.createElement("div");
    insightGrid.className = "ai-insight-grid";
    insightGrid.append(
        createInsightCard("📋", "状況", data.summary, "summary"),
        createInsightCard("💰", "収益判断", data.revenueInsight ?? "収益実績に基づく判断はありません。", "revenue"),
        createInsightCard("📊", "戦果評価", data.performanceDecision ?? "データ不足のため戦果評価はありません。", "analysis"),
        createInsightCard("🚀", "市場機会", data.marketOpportunity ?? "市場機会の提案はありません。", "market")
    );

    container.append(heading, insightGrid);

    const hasNextTask = typeof data.nextTask === "string" && data.nextTask.trim() !== "";
    if (!hasNextTask) {
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
    container.append(mission, footer);
    return approveButton;
}

function createInsightCard(icon, title, content, variant) {
    const card = document.createElement("article");
    card.className = `ai-insight-card ${variant}`;
    const heading = document.createElement("h4");
    heading.textContent = `${icon} ${title}`;
    const text = document.createElement("p");
    text.textContent = content;
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

document.querySelectorAll('input[type="number"]').forEach(input => {
    const selectDefaultZero = () => {
        if (input.value === "0") {
            input.select();
        }
    };

    input.addEventListener("focus", selectDefaultZero);
    input.addEventListener("click", selectDefaultZero);
});

revenueDate.valueAsDate = new Date();

commandButton.addEventListener("click", sendCommand);

commandInput.addEventListener("keydown", event => {
    if (event.key === "Enter") {
        sendCommand();
    }
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
        document.getElementById("revenue-amount").value = "0";
        document.getElementById("expense-amount").value = "0";
        document.getElementById("work-minutes").value = "0";
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
        document.getElementById("opportunity-revenue").value = "0";
        document.getElementById("opportunity-minutes").value = "0";
        message.textContent = "✅ 収益機会を登録しました。";
        await loadOpportunities();
    } catch (error) {
        message.textContent = "収益機会の登録に失敗しました。";
        console.error(error);
    } finally {
        button.disabled = false;
    }
});
