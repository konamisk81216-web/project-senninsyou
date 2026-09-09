import java.util.Scanner;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;

    public class Main{

        public static void main(String [] args){

        System.out.println("Project千人将 起動！");

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

        TaskRepository repository =
    new TaskRepository(jdbcUrl, user, password);

    AIService aiService = new AIService();

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
    scanner.nextLine();

    System.out.println("選択された命令：" + command);

    if (command == 1) {

    aiService.startGeneral();

    System.out.println("AI将軍への命令を入力してください：");
    String userMessage = scanner.nextLine();

    String taskStatus = repository.getAllTasksAsText();

    String prompt =
    aiService.buildGeneralPrompt(userMessage, taskStatus);

    System.out.println("===== AI将軍への命令書 =====");
    System.out.println(prompt);

    String fakeAiResponse = """
    {
      "summary": "現在のタスク状況を確認しました",
      "nextTask": "競合AIサービスを3つ調査する",
      "priority": "高",
      "assignedAgent": "偵察AI"
    }
    """;

TaskProposal proposal =
    aiService.parseTaskProposal(fakeAiResponse);

if (proposal != null) {
    System.out.println("===== AI将軍の提案 =====");
    System.out.println("要約：" + proposal.getSummary());
    System.out.println("次の任務：" + proposal.getNextTask());
    System.out.println("優先度：" + proposal.getPriority());
    System.out.println("担当：" + proposal.getAssignedAgent());

    System.out.println("この任務を登録しますか？");
    System.out.println("1：はい");
    System.out.println("0：いいえ");

    int approval = scanner.nextInt();
    scanner.nextLine();

    if (approval == 1) {

    repository.addTask(
        proposal.getNextTask(),
        proposal.getPriority(),
        proposal.getAssignedAgent()
    );

    System.out.println("AI将軍の提案を任務として登録しました。");

    } else {

        System.out.println("AI将軍の提案を却下しました。");
        }
    }
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
        System.out.println("4. タスクを削除");
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

        repository.addTask(taskName, priority, assignedAgent);

        System.out.println("タスク「" + taskName + "」を登録しました。");

        }

        if (taskCommand == 2) {
        System.out.println("=====タスク一覧=====");

        repository.showAllTasks();
    }

    if (taskCommand == 3) {

    System.out.println("状態を変更するタスクIDを入力してください");
    int taskId = scanner.nextInt();

    scanner.nextLine();

    System.out.println("新しい状態を入力してください（未着手・進行中・完了）");
    String newStatus = scanner.nextLine();

    repository.updateTaskStatus(taskId, newStatus);
    }

    if (taskCommand == 4) {

    System.out.println("削除するタスクIDを入力してください");
    int taskId = scanner.nextInt();

    repository.deleteTask(taskId);

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
    }
}
