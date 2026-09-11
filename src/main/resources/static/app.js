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
    const input = document.querySelector("input");
    const command = input.value.trim();

    if (command === "") {
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

        alert(result);

        input.value = "";

    } catch (error) {
        alert("AI将軍への命令送信に失敗しました。");
        console.error(error);
    }
}

const commandButton = document.querySelector("button");

commandButton.addEventListener("click", sendCommand);