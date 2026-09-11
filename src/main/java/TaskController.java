package com.senninsyou;

import java.net.URI;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskController {

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

        return tasks;
    }
}