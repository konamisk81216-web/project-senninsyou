package com.senninsyou;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    private static final String[] WRITER_MARKERS =
            {"[TITLE]", "[FREE]", "[PAID]", "[SALES]", "[SNS]", "[REVIEW]", "[END]"};

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

    @PostMapping("/writer")
    public Map<String, String> writer(@RequestBody WriterRequest request) {
        String theme = cleanWriterInput(request.theme(), "テーマ", 200, true);
        String audience = cleanWriterInput(request.audience(), "想定読者", 300, true);
        String sourceNotes = cleanWriterInput(request.sourceNotes(), "伝えたい内容", 4000, true);
        String price = cleanWriterInput(request.price(), "想定価格", 100, false);
        if (price.isBlank()) price = "未定";

        AIService.WriterAiResponse writerResponse = aiService.askWriter(
                aiService.buildWriterPrompt(theme, audience, sourceNotes, price));
        if (writerResponse.errorCode() != null) {
            return writerError(writerResponse.errorCode());
        }
        String response = writerResponse.text();
        if (response == null || response.isBlank()) {
            return writerError("WAI-EMPTY");
        }
        try {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("title", writerSection(response, "[TITLE]", "[FREE]"));
            result.put("freeSection", writerSection(response, "[FREE]", "[PAID]"));
            result.put("paidSection", writerSection(response, "[PAID]", "[SALES]"));
            result.put("salesDescription", writerSection(response, "[SALES]", "[SNS]"));
            result.put("snsPost", writerSection(response, "[SNS]", "[REVIEW]"));
            result.put("reviewNotes", writerLastSection(response, "[REVIEW]", "[END]"));
            for (String value : result.values()) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("必要な項目が不足しています。");
                }
            }
            return result;
        } catch (Exception e) {
            System.out.println("ライターAI診断: WAI-FORMAT");
            return writerError("WAI-FORMAT");
        }
    }

    private Map<String, String> writerError(String errorCode) {
        return Map.of("errorCode", errorCode);
    }

    private String writerSection(String response, String startMarker, String endMarker) {
        int start = response.indexOf(startMarker);
        if (start < 0) {
            throw new IllegalArgumentException("必要な区切りが不足しています。");
        }
        int contentStart = start + startMarker.length();
        int end = response.indexOf(endMarker, contentStart);
        if (end < 0) {
            throw new IllegalArgumentException("必要な区切りが不足しています。");
        }
        return cleanWriterSection(response.substring(contentStart, end));
    }

    private String writerLastSection(String response, String startMarker, String optionalEndMarker) {
        int start = response.indexOf(startMarker);
        if (start < 0) {
            throw new IllegalArgumentException("必要な区切りが不足しています。");
        }
        int contentStart = start + startMarker.length();
        int end = response.indexOf(optionalEndMarker, contentStart);
        if (end < 0) end = response.length();
        return cleanWriterSection(response.substring(contentStart, end));
    }

    // AIが区切りを本文中にも書いてしまうことがあるため、残りを取り除く。
    private String cleanWriterSection(String section) {
        String cleaned = section;
        for (String marker : WRITER_MARKERS) {
            cleaned = cleaned.replace(marker, "");
        }
        return cleaned.replaceAll("\n{3,}", "\n\n").trim();
    }

    private String cleanWriterInput(String value, String label, int maxLength, boolean required) {
        String cleaned = value == null ? "" : value.trim();
        if (required && cleaned.isBlank()) {
            throw new IllegalArgumentException(label + "を入力してください。");
        }
        if (cleaned.length() > maxLength) {
            throw new IllegalArgumentException(label + "が長すぎます。");
        }
        return cleaned;
    }

    public record CommandRequest(String message, String history, String mode) {}
    public record WriterRequest(String theme, String audience, String sourceNotes, String price) {}
}
