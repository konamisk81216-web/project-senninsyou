import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import java.sql.ResultSet;

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
        public void showAllTasks() {

    try {
        Connection connection =
            DriverManager.getConnection(jdbcUrl, user, password);

        String sql = "SELECT * FROM tasks ORDER BY id";

        PreparedStatement statement =
            connection.prepareStatement(sql);

        ResultSet result = statement.executeQuery();

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

            System.out.println(
                task.getId()
                + " | " + task.getTaskName()
                + " | " + task.getStatus()
                + " | " + task.getPriority()
                + " | " + task.getAssignedAgent()
            );
        }

        result.close();
        statement.close();
        connection.close();

    } catch (Exception e) {
        System.out.println("タスク一覧の取得に失敗しました。");
        e.printStackTrace();
            }
        }
            public void updateTaskStatus(int taskId, String newStatus) {

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

    } catch (Exception e) {
        System.out.println("タスク状態の変更に失敗しました。");
        e.printStackTrace();

                }
            }
            public void deleteTask(int taskId) {

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

    } catch (Exception e) {
        System.out.println("タスクの削除に失敗しました。");
        e.printStackTrace();
        }
    }
}