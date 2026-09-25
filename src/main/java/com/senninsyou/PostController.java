package com.senninsyou;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final AIService aiService = new AIService();
    private final PostDraftRepository repository;
    private final TaskRepository taskRepository;
    private final AuthorFactRepository authorFactRepository;
    private final RevenueContextService revenueContextService = new RevenueContextService();

    public PostController() {
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
        int port = dbUri.getPort() == -1 ? 5432 : dbUri.getPort();
        String jdbcUrl =
                "jdbc:postgresql://" + dbUri.getHost() + ":" + port + dbUri.getPath()
                + "?sslmode=require";

        repository = new PostDraftRepository(jdbcUrl, credentials[0], credentials[1]);
        taskRepository = new TaskRepository(jdbcUrl, credentials[0], credentials[1]);
        authorFactRepository = new AuthorFactRepository(jdbcUrl, credentials[0], credentials[1]);
    }

    @GetMapping
    public List<Map<String, String>> list() {
        return repository.getDrafts();
    }

    @PostMapping("/generate")
    public Map<String, String> generate(@RequestBody GenerateRequest request) {
        String note = request.note() == null ? "" : request.note().trim();
        if (note.length() > 1000) {
            throw new IllegalArgumentException("補足は1000文字以内で入力してください。");
        }

        String situation = """
                現在のタスク:
                %s

                収益の状況:
                %s

                記憶している本人の事実の件数: %d件
                """.formatted(
                        taskRepository.getAllTasksAsText(),
                        revenueContextService.buildContext(),
                        authorFactRepository.getAllFacts().size());

        AIService.WriterAiResponse response = aiService.askWriter(
                aiService.buildPostPrompt(situation, note, repository.buildRecentPostsText()));
        if (response.errorCode() != null) {
            return Map.of("errorCode", response.errorCode());
        }

        List<String[]> posts = parsePosts(response.text());
        if (posts.isEmpty()) {
            System.out.println("発信AI診断: WAI-FORMAT");
            return Map.of("errorCode", "WAI-FORMAT");
        }
        posts.forEach(post -> repository.addDraft(post[0], post[1]));
        return Map.of("status", posts.size() + "件の投稿案を作りました");
    }

    @PostMapping("/{id}/approve")
    public Map<String, String> approve(@PathVariable long id) {
        repository.updateStatus(id, "承認済み");
        return Map.of("status", "承認しました");
    }

    @PostMapping("/{id}/reject")
    public Map<String, String> reject(@PathVariable long id) {
        repository.updateStatus(id, "却下");
        return Map.of("status", "却下しました");
    }

    private List<String[]> parsePosts(String text) {
        List<String[]> posts = new ArrayList<>();
        if (text == null) {
            return posts;
        }
        int from = 0;
        while (true) {
            int postStart = text.indexOf("[POST]", from);
            if (postStart < 0) break;
            int intentStart = text.indexOf("[INTENT]", postStart);
            if (intentStart < 0) break;
            int end = text.indexOf("[END]", intentStart);
            if (end < 0) end = text.length();

            String content = text.substring(postStart + "[POST]".length(), intentStart).trim();
            String intent = text.substring(intentStart + "[INTENT]".length(), end).trim();

            if (!content.isBlank() && !intent.isBlank()) {
                posts.add(new String[]{content, intent.length() > 255 ? intent.substring(0, 255) : intent});
            }
            from = end + 1;
            if (posts.size() == 3) break;
        }
        return posts;
    }

    public record GenerateRequest(String note) {}
}
