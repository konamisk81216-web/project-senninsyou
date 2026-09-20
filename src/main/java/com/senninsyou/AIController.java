package com.senninsyou;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    // [TITLES]を[TITLE]より先に置く。短い方から消すと[TITLES]が壊れる。
    private static final String[] WRITER_MARKERS = {
            "[TITLES]", "[TITLE]", "[FREE]", "[PAID]", "[SALES]", "[SNS]", "[REVIEW]",
            "[PRICE]", "[PLAN]", "[METRICS]", "[DEMAND]", "[ANGLE]", "[MATERIAL]",
            "[SCORE]", "[VERDICT]", "[STRENGTHS]", "[IMPROVEMENTS]", "[QUESTIONS]", "[END]"};

    private final AIService aiService = new AIService();
    private final TaskRepository repository;
    private final WriterJobRepository writerJobRepository;
    // 同時に走らせない。無料枠のCPU時間とAPI費用を使いすぎないため。
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor();
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
        writerJobRepository = new WriterJobRepository(jdbcUrl, user, password);
    }

    @PreDestroy
    public void stopWriterExecutor() {
        writerExecutor.shutdown();
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

    @PostMapping("/writer/research")
    public Map<String, String> writerResearch(@RequestBody InterviewRequest request) {
        String theme = cleanWriterInput(request.theme(), "テーマ", 200, true);
        String audience = cleanWriterInput(request.audience(), "想定読者", 300, true);
        String sourceNotes = cleanWriterInput(request.sourceNotes(), "伝えたい内容", 4000, false);

        AIService.WriterAiResponse aiResponse = aiService.askWriter(
                aiService.buildResearchPrompt(theme, audience, sourceNotes));
        if (aiResponse.errorCode() != null) {
            return writerError(aiResponse.errorCode());
        }
        String response = aiResponse.text();
        if (response == null || response.isBlank()) {
            return writerError("WAI-EMPTY");
        }
        try {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("demand", writerSection(response, "[DEMAND]", "[ANGLE]"));
            result.put("angle", writerSection(response, "[ANGLE]", "[MATERIAL]"));
            result.put("material", writerLastSection(response, "[MATERIAL]", "[END]"));
            for (String value : result.values()) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("必要な項目が不足しています。");
                }
            }
            return result;
        } catch (Exception e) {
            System.out.println("軍師AI診断: WAI-FORMAT");
            return writerError("WAI-FORMAT");
        }
    }

    @PostMapping("/writer/interview")
    public Map<String, Object> writerInterview(@RequestBody InterviewRequest request) {
        String theme = cleanWriterInput(request.theme(), "テーマ", 200, true);
        String audience = cleanWriterInput(request.audience(), "想定読者", 300, true);
        String sourceNotes = cleanWriterInput(request.sourceNotes(), "伝えたい内容", 4000, false);

        AIService.WriterAiResponse aiResponse = aiService.askWriter(
                aiService.buildInterviewPrompt(theme, audience, sourceNotes));
        if (aiResponse.errorCode() != null) {
            return Map.of("errorCode", aiResponse.errorCode());
        }
        List<String> questions = parseInterviewQuestions(aiResponse.text());
        if (questions.isEmpty()) {
            System.out.println("ライターAI診断: WAI-FORMAT（取材）");
            return Map.of("errorCode", "WAI-FORMAT");
        }
        return Map.of("questions", questions);
    }

    private List<String> parseInterviewQuestions(String text) {
        List<String> questions = new ArrayList<>();
        if (text == null) {
            return questions;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("[Q]")) {
                continue;
            }
            String question = trimmed.substring("[Q]".length()).trim();
            if (!question.isBlank()) {
                questions.add(question);
            }
            if (questions.size() == 5) {
                break;
            }
        }
        return questions;
    }

    @PostMapping("/writer/jobs")
    public Map<String, String> createWriterJob(@RequestBody WriterRequest request) {
        String theme = cleanWriterInput(request.theme(), "テーマ", 200, true);
        String audience = cleanWriterInput(request.audience(), "想定読者", 300, true);
        String sourceNotes = cleanWriterInput(request.sourceNotes(), "伝えたい内容", 4000, true);
        String price = cleanWriterInput(request.price(), "想定価格", 100, false);
        String interviewNotes = cleanWriterInput(request.interviewNotes(), "取材メモ", 6000, false);
        String jobPrice = price.isBlank() ? "未定" : price;

        long id = writerJobRepository.createJob(theme, audience, jobPrice, sourceNotes, interviewNotes);
        if (id < 0) {
            return writerError("WAI-DB");
        }
        writerExecutor.submit(
                () -> runWriterJob(id, theme, audience, sourceNotes, jobPrice, interviewNotes));
        return Map.of("id", String.valueOf(id), "status", "受付");
    }

    @GetMapping("/writer/jobs/latest")
    public Map<String, String> latestWriterJob() {
        return writerJobRepository.findLatestJob();
    }

    @GetMapping("/writer/jobs/{id}")
    public Map<String, String> writerJob(@PathVariable long id) {
        return writerJobRepository.findJob(id);
    }

    // ブラウザを閉じても執筆が続くよう、依頼を受け付けた後は裏で実行する。
    private void runWriterJob(
            long id,
            String theme,
            String audience,
            String sourceNotes,
            String price,
            String interviewNotes) {

        writerJobRepository.markWriting(id);
        Map<String, String> result =
                writeDraft(theme, audience, sourceNotes, price, interviewNotes);

        if (result.containsKey("errorCode")) {
            writerJobRepository.saveFailure(id, result.get("errorCode"));
        } else {
            writerJobRepository.saveResult(id, result);
        }
    }

    private Map<String, String> writeDraft(
            String theme,
            String audience,
            String sourceNotes,
            String price,
            String interviewNotes) {

        AIService.WriterAiResponse writerResponse = aiService.askWriter(
                aiService.buildWriterPrompt(theme, audience, sourceNotes, price, interviewNotes));
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

    @PostMapping("/writer/marketing")
    public Map<String, String> writerMarketing(@RequestBody MarketingRequest request) {
        String theme = cleanWriterInput(request.theme(), "テーマ", 200, true);
        String audience = cleanWriterInput(request.audience(), "想定読者", 300, true);
        String title = cleanWriterInput(request.title(), "タイトル", 300, true);
        String freeSection = cleanWriterInput(request.freeSection(), "無料部分", 4000, true);
        String paidSection = cleanWriterInput(request.paidSection(), "有料部分", 8000, false);
        String price = cleanWriterInput(request.price(), "想定価格", 100, false);
        if (price.isBlank()) price = "未定";
        if (paidSection.length() > 2000) {
            paidSection = paidSection.substring(0, 2000);
        }

        AIService.WriterAiResponse aiResponse = aiService.askWriter(
                aiService.buildMarketingPrompt(
                        theme, audience, price, title, freeSection, paidSection));
        if (aiResponse.errorCode() != null) {
            return writerError(aiResponse.errorCode());
        }
        String response = aiResponse.text();
        if (response == null || response.isBlank()) {
            return writerError("WAI-EMPTY");
        }
        try {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("titleIdeas", writerSection(response, "[TITLES]", "[PRICE]"));
            result.put("priceAdvice", writerSection(response, "[PRICE]", "[PLAN]"));
            result.put("promotionPlan", writerSection(response, "[PLAN]", "[METRICS]"));
            result.put("metrics", writerLastSection(response, "[METRICS]", "[END]"));
            for (String value : result.values()) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("必要な項目が不足しています。");
                }
            }
            return result;
        } catch (Exception e) {
            System.out.println("営業AI診断: WAI-FORMAT");
            return writerError("WAI-FORMAT");
        }
    }

    @PostMapping("/writer/quality")
    public Object writerQuality(@RequestBody WriterQualityRequest request) {
        String theme = cleanWriterInput(request.theme(), "テーマ", 200, true);
        String audience = cleanWriterInput(request.audience(), "想定読者", 300, true);
        String price = cleanWriterInput(request.price(), "想定価格", 100, false);
        String title = cleanWriterInput(request.title(), "タイトル", 300, true);
        String freeSection = cleanWriterInput(request.freeSection(), "無料部分", 12000, true);
        String paidSection = cleanWriterInput(request.paidSection(), "有料部分", 20000, true);
        String salesDescription = cleanWriterInput(request.salesDescription(), "販売説明", 6000, true);
        if (price.isBlank()) price = "未定";

        AIService.WriterAiResponse response = aiService.askWriter(aiService.buildWriterQualityPrompt(
                theme, audience, price, title, freeSection, paidSection, salesDescription));
        if (response.errorCode() != null) return writerError(response.errorCode());
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            String scoreText = writerSection(response.text(), "[SCORE]", "[VERDICT]").replaceAll("[^0-9]", "");
            int score = Integer.parseInt(scoreText);
            if (score < 0 || score > 100) throw new IllegalArgumentException("点数が範囲外です。");
            result.put("score", score);
            result.put("verdict", writerSection(response.text(), "[VERDICT]", "[STRENGTHS]"));
            result.put("strengths", writerSection(response.text(), "[STRENGTHS]", "[IMPROVEMENTS]"));
            result.put("improvements", writerSection(response.text(), "[IMPROVEMENTS]", "[QUESTIONS]"));
            result.put("questions", writerLastSection(response.text(), "[QUESTIONS]", "[END]"));
            return result;
        } catch (Exception e) {
            System.out.println("品質管理AI診断: WAI-FORMAT");
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
    public record InterviewRequest(String theme, String audience, String sourceNotes) {}
    public record MarketingRequest(
            String theme, String audience, String price,
            String title, String freeSection, String paidSection) {}
    public record WriterRequest(
            String theme, String audience, String sourceNotes, String price, String interviewNotes) {}
    public record WriterQualityRequest(
            String theme, String audience, String price,
            String title, String freeSection, String paidSection, String salesDescription) {}
}
