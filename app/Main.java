import java.util.Scanner;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;

    public class Main{

        public static void main(String [] args){

        System.out.println("🏰 Project千人将 起動！");

        Scanner scanner = new Scanner(System.in, "MS932");

        String url = System.getenv("DATABASE_URL");

if (url == null) {
    System.out.println("DATABASE_URLが見つかりません。");
    return;
}

java.net.URI dbUri = java.net.URI.create(url);

String userInfo = dbUri.getUserInfo();
String[] userParts = userInfo.split(":", 2);

String user = userParts[0];
String password = userParts[1];

String jdbcUrl = "jdbc:postgresql://"
        + dbUri.getHost()
        + dbUri.getPath()
        + "?sslmode=require";

        int command = -1;

    while  (command != 0) {

        System.out.println("========================");
    System.out.println("      Project 千人将");
    System.out.println("========================");
    System.out.println("1. AI秘書");
    System.out.println("2. タスク管理");
    System.out.println("3. 収益化支援");
    System.out.println("0. 終了");
    System.out.println("========================");
    System.out.println("命令を選択してください：");

    command = scanner.nextInt();

    System.out.println("選択された命令：" + command);

    if (command==1){
        System.out.println("AI秘書を起動します。");
    }

    if(command==2){

        int taskCommand = -1;

        while (taskCommand != 0) {

        System.out.println("========================");
        System.out.println("        タスク管理");
        System.out.println("========================");
        System.out.println("1. タスクを追加");
        System.out.println("2. タスク一覧");
        System.out.println("3. タスク状態を変更");
        System.out.println("0. メインメニューに戻る");
        System.out.println("========================");

        System.out.println("操作を選択してください");

        taskCommand = scanner.nextInt();

        if(taskCommand == 1) {
            System.out.println("タスク名を入力してください");

            scanner.nextLine();
            String taskName = scanner.nextLine();

            System.out.println("優先度を入力してください（高・中・低）");
            String priority = scanner.nextLine();

            System.out.println("担当AIを入力してください（例：偵察AI、軍師AI）");
            String assignedAgent = scanner.nextLine();

        try {
            Connection connection =
                    DriverManager.getConnection(jdbcUrl, user, password);

            String sql = "INSERT INTO tasks (task_name, priority, assigned_agent) VALUES (?, ?, ?)";

            PreparedStatement insertStatement =
                    connection.prepareStatement(sql);

            insertStatement.setString(1, taskName);
            insertStatement.setString(2, priority);
            insertStatement.setString(3, assignedAgent);

            insertStatement.executeUpdate();

            System.out.println("タスク「" + taskName + "」を登録しました。");

            insertStatement.close();
            connection.close();

        } catch (Exception e) {
            System.out.println("タスクの登録に失敗しました。");
            e.printStackTrace();
        }
    }
    
    if(taskCommand == 2){
    System.out.println("=====タスク一覧=====");

    try {
        Connection connection =
                DriverManager.getConnection(jdbcUrl, user, password);

        Statement statement = connection.createStatement();

        ResultSet result =
                statement.executeQuery("SELECT * FROM tasks ORDER BY id");

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

    if (taskCommand == 3) {

    System.out.println("状態を変更するタスクIDを入力してください");
    int taskId = scanner.nextInt();

    scanner.nextLine();

    System.out.println("新しい状態を入力してください（未着手・進行中・完了）");
    String newStatus = scanner.nextLine();

    try {
    Connection connection =
        DriverManager.getConnection(jdbcUrl, user, password);

    String sql =
        "UPDATE tasks SET status = ? WHERE id = ?";

    PreparedStatement updateStatement =
        connection.prepareStatement(sql);

    updateStatement.setString(1, newStatus);
    updateStatement.setInt(2, taskId);

    int rows = updateStatement.executeUpdate();

    System.out.println(rows + "件のタスク状態を変更しました。");

    updateStatement.close();
    connection.close();

    } 
    
    catch (Exception e) {
    System.out.println("タスク状態の変更に失敗しました。");
    e.printStackTrace();
            }
        }
    }
}
    if(command==3){
        System.out.println("収益化支援を起動します。");
    }

    if(command==0){
        System.out.println("Project千人将を終了します。");
    }

    if(command<0 || command>3){
        System.out.println("その命令は存在しません。");

    }

System.out.println();

    }
    scanner.close();

    }
}