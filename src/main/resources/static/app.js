async function loadTasks() {
    const taskList = document.getElementById("task-list");

    try {
        const response = await fetch("/api/tasks");
        const tasks = await response.json();

        const totalTasks = document.getElementById("total-tasks");
        const inProgressTasks = document.getElementById("in-progress-tasks");
        const completedTasks = document.getElementById("completed-tasks");
        const notStartedTasks = document.getElementById("not-started-tasks");

        const inProgressCount = tasks.filter(task => task.status === "進行中").length;
        const completedCount = tasks.filter(task => task.status === "完了").length;
        const notStartedCount = tasks.filter(task => task.status === "未着手").length;

        totalTasks.textContent = `総任務：${tasks.length}`;
        inProgressTasks.textContent = `進行中：${inProgressCount}`;
        completedTasks.textContent = `完了：${completedCount}`;
        notStartedTasks.textContent = `未着手：${notStartedCount}`;

        if (!tasks || tasks.length === 0) {
            taskList.innerHTML = "<p>現在、任務はありません。</p>";
            return;
        }

        taskList.innerHTML = "";

        tasks.forEach(task => {
            const taskCard = document.createElement("div");
            taskCard.className = "task-card";

            const title = document.createElement("h3");
            title.textContent = task.taskName;

            const priority = document.createElement("p");
            priority.textContent = `優先度：${task.priority}`;

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

    loadTasks();

    async function sendCommand() {
    const input = document.querySelector('input');
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

        aiResponse.innerHTML = `
            <h3>👑 AI将軍の回答</h3>

            <p><strong>📋 状況</strong></p>
            <p>${data.summary}</p>

            <p><strong>⚔️ 次の任務</strong></p>
            <p>${data.nextTask}</p>

            <p><strong>🔥 優先度</strong></p>
            <p>${data.priority}</p>

            <p><strong>🤖 担当</strong></p>
            <p>${data.assignedAgent}</p>

            <button id="approve-task">⚔️ この任務を登録</button>
        `;

        const approveButton = document.getElementById("approve-task");

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

        input.value = "";

    } catch (error) {
        alert("AI将軍への命令送信に失敗しました。");
        console.error(error);
    }
}

const commandButton = document.querySelector("button");
commandButton.addEventListener("click", sendCommand);
