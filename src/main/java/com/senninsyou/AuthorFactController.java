package com.senninsyou;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/author-facts")
public class AuthorFactController {

    private final AuthorFactRepository repository;

    public AuthorFactController() {
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

        repository = new AuthorFactRepository(jdbcUrl, credentials[0], credentials[1]);
    }

    @GetMapping
    public List<Map<String, String>> list() {
        return repository.getAllFacts();
    }

    @PostMapping
    public Map<String, String> add(@RequestBody FactRequest request) {
        repository.addFact(
                cleanCategory(request.category()),
                clean(request.topic(), "項目", 255),
                clean(request.fact(), "内容", 4000));
        return Map.of("status", "保存しました");
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable long id) {
        repository.deleteFact(id);
        return Map.of("status", "削除しました");
    }

    private String cleanCategory(String category) {
        List<String> allowed = List.of("経歴", "実績", "道具", "失敗", "方針", "その他");
        return allowed.contains(category) ? category : "その他";
    }

    private String clean(String value, String label, int maxLength) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException(label + "を入力してください。");
        }
        if (cleaned.length() > maxLength) {
            throw new IllegalArgumentException(label + "が長すぎます。");
        }
        return cleaned;
    }

    public record FactRequest(String category, String topic, String fact) {}
}
