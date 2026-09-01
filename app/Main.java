import java.util.Scanner;
import java.util.ArrayList;

    public class Main{

        public static void main(String [] args){

        System.out.println("🏰 Project千人将 起動！");

        Scanner scanner = new Scanner(System.in, "MS932");
    
        ArrayList<String> tasks = new ArrayList<>();

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
        System.out.println("0. メインメニューに戻る");
        System.out.println("========================");

        System.out.println("操作を選択してください");

        taskCommand = scanner.nextInt();

        if(taskCommand == 1) {
            System.out.println("タスク名を入力してください");

            scanner.nextLine();
            String taskName = scanner.nextLine();

            tasks.add(taskName);
            System.out.println("タスク「" + taskName + "」を登録しました。");
        }
    
    if(taskCommand == 2){
        System.out.println("=====タスク一覧=====");

        for (String task : tasks){
            System.out.println(task);
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