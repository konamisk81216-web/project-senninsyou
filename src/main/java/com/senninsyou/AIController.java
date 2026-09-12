package com.senninsyou;

import java.net.URI;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    private final AIService aiService = new AIService();
    private final TaskRepository repository;

    public AIController() {

        String databaseUrl = System.getenv("DATABASE_URL");

        if (databaseUrl == null) {
            throw new IllegalStateException("DATABASE_URLが設定されていません。");
        }

        URI dbUri = URI.create(databaseUrl);

        String userInfo = dbUri.getUserInfo();

        if (userInfo == null || !userInfo.contains(":")) {
            throw new IllegalStateException("DATABASE_URLの形式が正しくありません。");
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

        repository = new TaskRepository(jdbcUrl, user, password);
    }

    @PostMapping("/command")
    public String command(@RequestBody String command) {

        String taskStatus = repository.getAllTasksAsText();

        if (taskStatus.equals("データベース接続に失敗しています。")) {
            return "AI将軍を起動できません。データベース接続を確認してください。";
        }

        String prompt =
                aiService.buildGeneralPrompt(command, taskStatus);

        String response =
                aiService.askGeneral(prompt);

        if (response == null || response.isBlank()) {
            return "AI将軍から有効な返答を受け取れませんでした。";
        }

        return response;
    }
}