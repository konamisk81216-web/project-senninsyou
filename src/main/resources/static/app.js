async function loadTasks() {
    const taskList = document.getElementById("task-list");

    try {
        const response = await fetch("/api/tasks");
        const tasks = await response.json();

        if (!tasks || tasks.length === 0) {
            taskList.innerHTML = "<p>現在、任務はありません。</p>";
            return;
        }

        taskList.innerHTML = "";

        tasks.forEach(task => {
            const taskCard = document.createElement("div");
            taskCard.className = "task-card";

            taskCard.innerHTML = `
                <h3>${task.taskName}</h3>
                <p>優先度：${task.priority}</p>
                <p>担当：${task.assignedAgent ?? "未設定"}</p>
                <p>状態：${task.status}</p>
            `;

            taskList.appendChild(taskCard);
        });

    } catch (error) {
        taskList.innerHTML = "<p>任務の取得に失敗しました。</p>";
        console.error(error);
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