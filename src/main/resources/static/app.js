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