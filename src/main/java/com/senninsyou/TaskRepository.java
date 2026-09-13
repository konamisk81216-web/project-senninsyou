package com.senninsyou;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import java.sql.ResultSet;

import java.util.ArrayList;
import java.util.List;

public class TaskRepository {

    private String jdbcUrl;
    private String user;
    private String password;

    public TaskRepository(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    public void addTask(String taskName, String priority, String assignedAgent) {

        try {
            Connection connection =
                DriverManager.getConnection(jdbcUrl, user, password);

            String sql =
                "INSERT INTO tasks (task_name, priority, assigned_agent) VALUES (?, ?, ?)";

            PreparedStatement statement =
                connection.prepareStatement(sql);

            statement.setString(1, taskName);
            statement.setString(2, priority);
            statement.setString(3, assignedAgent);

            statement.executeUpdate();

            statement.close();
            connection.close();

        } 
        catch (Exception e) {
            System.out.println("タスク登録に失敗しました。");
            e.printStackTrace();
        }
    }

        public List<Task> getAllTasks() {

    List<Task> tasks = new ArrayList<>();

    String sql = "SELECT * FROM tasks ORDER BY id";

    try (
        Connection connection =
            DriverManager.getConnection(jdbcUrl, user, password);

        PreparedStatement statement =
            connection.prepareStatement(sql);

        ResultSet result =
            statement.executeQuery()
    ) {

        while (result.next()) {

            int id = result.getInt("id");
            String taskName = result.getString("task_name");
            String status = result.getString("status");
            String priority = result.getString("priority");
            String assignedAgent = result.getString("assigned_agent");

            Task task = new Task(
                id,
                taskName,
                status,
                priority,
                assignedAgent
            );

            tasks.add(task);
        }

        } catch (Exception e) {
        System.out.println("タスク一覧の取得に失敗しました。");
        System.out.println("データベース接続を確認してください。");
        return null;
        }
        return tasks;
    }


public void showAllTasks() {

    List<Task> tasks = getAllTasks();

    for (Task task : tasks) {

        System.out.println(
            task.getId()
            + " | " + task.getTaskName()
            + " | " + task.getStatus()
            + " | " + task.getPriority()
            + " | " + task.getAssignedAgent()
        );
    }
}


public String getAllTasksAsText() {

    List<Task> tasks = getAllTasks();

    if (tasks == null) {
    return "データベース接続に失敗しています。";
    }

    if (tasks.isEmpty()) {
        return "現在タスクはありません。";
    }

    StringBuilder text = new StringBuilder();

    for (Task task : tasks) {

        text.append(task.getId())
            .append(" | ")
            .append(task.getTaskName())
            .append(" | ")
            .append(task.getStatus())
            .append(" | ")
            .append(task.getPriority())
            .append(" | ")
            .append(task.getAssignedAgent())
            .append("\n");
    }

    return text.toString();
}
            public boolean updateTaskStatus(int taskId, String newStatus) {

    try {
        Connection connection =
            DriverManager.getConnection(jdbcUrl, user, password);

        String sql =
            "UPDATE tasks SET status = ? WHERE id = ?";

        PreparedStatement statement =
            connection.prepareStatement(sql);

        statement.setString(1, newStatus);
        statement.setInt(2, taskId);

        int rows = statement.executeUpdate();

        System.out.println(rows + "件のタスク状態を変更しました。");

        statement.close();
        connection.close();
        return rows > 0;

    } catch (Exception e) {
        System.out.println("タスク状態の変更に失敗しました。");
        e.printStackTrace();
        return false;
                }
            }
            public boolean deleteTask(int taskId) {

    try {
        Connection connection =
            DriverManager.getConnection(jdbcUrl, user, password);

        String sql =
            "DELETE FROM tasks WHERE id = ?";

        PreparedStatement statement =
            connection.prepareStatement(sql);

        statement.setInt(1, taskId);

        int rows = statement.executeUpdate();

        System.out.println(rows + "件のタスクを削除しました。");

        statement.close();
        connection.close();
        return rows > 0;

    } catch (Exception e) {
        System.out.println("タスクの削除に失敗しました。");
        e.printStackTrace();
        return false;
        }
    }
}
