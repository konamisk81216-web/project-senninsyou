package com.senninsyou;

import java.net.URI;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    private final AIService aiService = new AIService();
    private final TaskRepository repository;
    private final RevenueContextService revenueContextService =
            new RevenueContextService();

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
    public String command(@RequestBody CommandRequest request) {

        String command = request.message() == null ? "" : request.message().trim();
        if (command.isBlank() || command.length() > 2000) {
            throw new IllegalArgumentException("命令は1〜2000文字で入力してください。");
        }
        String history = request.history() == null ? "" : request.history();
        if (history.length() > 12000) {
            history = history.substring(history.length() - 12000);
        }
        boolean propose = "propose".equals(request.mode()) && !history.isBlank();

        String taskStatus = repository.getAllTasksAsText();

        if (taskStatus.equals("データベース接続に失敗しています。")) {
            return "AI将軍を起動できません。データベース接続を確認してください。";
        }

        String revenueStatus = revenueContextService.buildContext();

        String prompt =
                aiService.buildGeneralPrompt(
                        command, taskStatus, revenueStatus, history, propose);

        String response =
                aiService.askGeneral(prompt);

        if (response == null || response.isBlank()) {
            return "AI将軍から有効な返答を受け取れませんでした。";
        }

        try {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("JSONを見つけられませんでした。");
            }
            ObjectNode result = (ObjectNode) new ObjectMapper()
                    .readTree(response.substring(start, end + 1));
            if (!propose) {
                result.put("nextTask", "");
                result.put("priority", "");
                result.put("assignedAgent", "");
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("AI将軍の返答形式が正しくありません。", e);
        }
    }

    public record CommandRequest(String message, String history, String mode) {}
}
