package com.senninsyou;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsRepository repository;

    public MetricsController() {
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

        repository = new MetricsRepository(jdbcUrl, credentials[0], credentials[1]);
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of(
                "entries", repository.getMetrics(),
                "costs", repository.getCosts(),
                "summary", repository.getSummary());
    }

    @PostMapping
    public Map<String, String> addMetric(@RequestBody MetricRequest request) {
        String title = clean(request.title(), "記事名", 255);
        String measuredOn = clean(request.measuredOn(), "計測日", 10);
        if (!measuredOn.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("計測日の形式が正しくありません。");
        }
        repository.addMetric(
                title,
                measuredOn,
                positive(request.views(), "閲覧数"),
                positive(request.clicks(), "クリック数"),
                positive(request.purchases(), "購入件数"),
                request.memo() == null ? "" : request.memo().trim());
        return Map.of("status", "記録しました");
    }

    @DeleteMapping("/{id}")
    public Map<String, String> deleteMetric(@PathVariable long id) {
        repository.deleteMetric(id);
        return Map.of("status", "削除しました");
    }

    @PostMapping("/costs")
    public Map<String, String> addCost(@RequestBody CostRequest request) {
        String month = clean(request.month(), "対象月", 7);
        if (!month.matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("対象月の形式が正しくありません。");
        }
        if (request.amount() == null || request.amount() < 0) {
            throw new IllegalArgumentException("金額を正しく入力してください。");
        }
        repository.addCost(month, clean(request.service(), "サービス名", 100), request.amount());
        return Map.of("status", "記録しました");
    }

    @DeleteMapping("/costs/{id}")
    public Map<String, String> deleteCost(@PathVariable long id) {
        repository.deleteCost(id);
        return Map.of("status", "削除しました");
    }

    private int positive(Integer value, String label) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(label + "を正しく入力してください。");
        }
        return value;
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

    public record MetricRequest(
            String title, String measuredOn,
            Integer views, Integer clicks, Integer purchases, String memo) {}

    public record CostRequest(String month, String service, Double amount) {}
}
