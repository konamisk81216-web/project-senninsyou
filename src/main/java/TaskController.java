package com.senninsyou;

import java.net.URI;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
public class TaskController {

    @PutMapping("/api/tasks/{id}")
    public ResponseEntity<String> editTask(@PathVariable int id,
            @RequestBody Map<String, String> taskData) {
        String taskName = taskData.get("taskName");
        String priority = taskData.get("priority");
        String assignedAgent = taskData.get("assignedAgent");
        if (id <= 0 || taskName == null || taskName.isBlank() || taskName.length() > 200
                || priority == null || !List.of("高", "中", "低").contains(priority)
                || assignedAgent == null || assignedAgent.length() > 100) {
            return ResponseEntity.badRequest().body("タスク名・優先度・担当を確認してください。");
        }
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl == null) {
            return ResponseEntity.internalServerError().body("DATABASE_URLが設定されていません。");
        }
        URI dbUri = URI.create(databaseUrl);
        String userInfo = dbUri.getUserInfo();
        if (userInfo == null || !userInfo.contains(":")) {
            return ResponseEntity.internalServerError().body("DATABASE_URLの形式が正しくありません。");
        }
        String[] credentials = userInfo.split(":", 2);
        int port = dbUri.getPort() == -1 ? 5432 : dbUri.getPort();
        String jdbcUrl = "jdbc:postgresql://" + dbUri.getHost() + ":" + port
                + dbUri.getPath() + "?sslmode=require";
        TaskRepository repository = new TaskRepository(jdbcUrl, credentials[0], credentials[1]);
        try {
            if (!repository.updateTask(id, taskName.trim(), priority, assignedAgent.trim())) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok("タスクを更新しました。");
        } catch (IllegalStateException e) {
            return ResponseEntity.internalServerError().body("タスク編集に失敗しました。");
        }
    }

    @PutMapping("/api/tasks/{id}/note-stage")
    public ResponseEntity<String> updateNoteStage(@PathVariable int id,
            @RequestBody Map<String, String> taskData) {
        String noteStage = taskData.get("noteStage");
        if (id <= 0 || noteStage == null
                || !List.of("企画", "制作", "公開", "販売", "実績").contains(noteStage)) {
            return ResponseEntity.badRequest().body("正しい進行段階を指定してください。");
        }
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl == null) {
            return ResponseEntity.internalServerError().body("DATABASE_URLが設定されていません。");
        }
        URI dbUri = URI.create(databaseUrl);
        String userInfo = dbUri.getUserInfo();
        if (userInfo == null || !userInfo.contains(":")) {
            return ResponseEntity.internalServerError().body("DATABASE_URLの形式が正しくありません。");
        }
        String[] credentials = userInfo.split(":", 2);
        int port = dbUri.getPort() == -1 ? 5432 : dbUri.getPort();
        String jdbcUrl = "jdbc:postgresql://" + dbUri.getHost() + ":" + port
                + dbUri.getPath() + "?sslmode=require";
        TaskRepository repository = new TaskRepository(jdbcUrl, credentials[0], credentials[1]);
        try {
            if (!repository.updateNoteStage(id, noteStage)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok("進行段階を更新しました。");
        } catch (IllegalStateException e) {
            return ResponseEntity.internalServerError().body("進行段階の更新に失敗しました。");
        }
    }

    @GetMapping("/api/tasks")
    public List<Task> getTasks() {

        String databaseUrl = System.getenv("DATABASE_URL");

        if (databaseUrl == null) {
            return null;
        }

        URI dbUri = URI.create(databaseUrl);

        String userInfo = dbUri.getUserInfo();

        if (userInfo == null || !userInfo.contains(":")) {
            return null;
        }

        String[] credentials = userInfo.split(":", 2);

        String user = credentials[0];
        String password = credentials[1];

        int port = dbUri.getPort();

            if (port == -1) {
            port = 5432;
            }

        String jdbcUrl =
        "jdbc:postgresql://" +
        dbUri.getHost() +
        ":" +
        port +
        dbUri.getPath() +
        "?sslmode=require";

        TaskRepository repository =
            new TaskRepository(jdbcUrl, user, password);

        List<Task> tasks = repository.getAllTasks();

        System.out.println("タスク取得結果: " + (tasks == null ? "null" : tasks.size() + "件"));

        return tasks;}

        @PostMapping("/api/tasks")
    public String addTask(@RequestBody Map<String, String> taskData) {

    String databaseUrl = System.getenv("DATABASE_URL");

    if (databaseUrl == null) {
        return "DATABASE_URLが設定されていません。";
    }

    URI dbUri = URI.create(databaseUrl);

    String userInfo = dbUri.getUserInfo();

    if (userInfo == null || !userInfo.contains(":")) {
        return "DATABASE_URLの形式が正しくありません。";
    }

    String[] credentials = userInfo.split(":", 2);

    String user = credentials[0];
    String password = credentials[1];

    int port = dbUri.getPort();

    if (port == -1) {
        port = 5432;
    }

    String jdbcUrl =
            "jdbc:postgresql://" +
            dbUri.getHost() +
            ":" +
            port +
            dbUri.getPath() +
            "?sslmode=require";

    TaskRepository repository =
            new TaskRepository(jdbcUrl, user, password);

    repository.addTask(
            taskData.get("taskName"),
            taskData.get("priority"),
            taskData.get("assignedAgent")
    );

    return "任務を登録しました。";
        
    }
    @PostMapping("/api/tasks/status")
    public ResponseEntity<String> updateTaskStatus(
            @RequestBody Map<String, String> taskData) {

    String databaseUrl = System.getenv("DATABASE_URL");

    if (databaseUrl == null) {
        return ResponseEntity.internalServerError()
                .body("DATABASE_URLが設定されていません。");
    }

    URI dbUri = URI.create(databaseUrl);

    String userInfo = dbUri.getUserInfo();

    if (userInfo == null || !userInfo.contains(":")) {
        return ResponseEntity.internalServerError()
                .body("DATABASE_URLの形式が正しくありません。");
    }

    String[] credentials = userInfo.split(":", 2);

    String user = credentials[0];
    String password = credentials[1];

    int port = dbUri.getPort();

    if (port == -1) {
        port = 5432;
    }

    String jdbcUrl =
            "jdbc:postgresql://" +
            dbUri.getHost() +
            ":" +
            port +
            dbUri.getPath() +
            "?sslmode=require";

    TaskRepository repository =
            new TaskRepository(jdbcUrl, user, password);

    int taskId;

    try {
        taskId = Integer.parseInt(taskData.get("id"));
    } catch (NumberFormatException | NullPointerException e) {
        return ResponseEntity.badRequest()
                .body("正しい任務IDを指定してください。");
    }

    String status = taskData.get("status");

    if (!List.of("未着手", "進行中", "完了").contains(status)) {
        return ResponseEntity.badRequest()
                .body("状態は未着手・進行中・完了のいずれかを指定してください。");
    }

    if (!repository.updateTaskStatus(taskId, status)) {
        return ResponseEntity.notFound().build();
    }

    return ResponseEntity.ok("タスク状態を更新しました。");
    }

    @DeleteMapping("/api/tasks/{id}")
    public ResponseEntity<String> deleteTask(@PathVariable int id) {

        String databaseUrl = System.getenv("DATABASE_URL");

        if (databaseUrl == null) {
            return ResponseEntity.internalServerError()
                    .body("DATABASE_URLが設定されていません。");
        }

        URI dbUri = URI.create(databaseUrl);
        String userInfo = dbUri.getUserInfo();

        if (userInfo == null || !userInfo.contains(":")) {
            return ResponseEntity.internalServerError()
                    .body("DATABASE_URLの形式が正しくありません。");
        }

        String[] credentials = userInfo.split(":", 2);
        int port = dbUri.getPort() == -1 ? 5432 : dbUri.getPort();

        String jdbcUrl =
                "jdbc:postgresql://" +
                dbUri.getHost() +
                ":" +
                port +
                dbUri.getPath() +
                "?sslmode=require";

        TaskRepository repository =
                new TaskRepository(jdbcUrl, credentials[0], credentials[1]);

        if (!repository.deleteTask(id)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok("任務を削除しました。");
    }
}
