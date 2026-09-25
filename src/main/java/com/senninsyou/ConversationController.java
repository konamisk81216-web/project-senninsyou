package com.senninsyou;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationRepository repository;

    public ConversationController() {
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

        repository = new ConversationRepository(jdbcUrl, credentials[0], credentials[1]);
    }

    @GetMapping
    public List<Map<String, String>> recent() {
        return repository.recent(20);
    }

    @DeleteMapping
    public Map<String, String> clear() {
        repository.clear();
        return Map.of("status", "会話履歴を削除しました");
    }
}
