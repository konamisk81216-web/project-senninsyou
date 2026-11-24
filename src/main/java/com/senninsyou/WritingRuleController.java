package com.senninsyou;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 書き方の決まりの一覧・追加・編集・有効と無効の切り替え。タスク管理画面から使う。
// 削除はしない（決まりの履歴を残す）。失敗は、失敗の状態と JSON の {error, errorCode} で返す。
@RestController
@RequestMapping("/api/writing-rules")
public class WritingRuleController {

    static final int MAX_RULE_LENGTH = 500;
    static final int MAX_REASON_LENGTH = 1000;

    private final WritingRuleRepository repository;

    public WritingRuleController() {
        Database.Settings settings = Database.settings();
        this.repository = new WritingRuleRepository(settings.jdbcUrl(), settings.user(), settings.password());
    }

    WritingRuleController(WritingRuleRepository repository) {
        this.repository = repository;
    }

    // 入力の誤り（400）。文は画面にそのまま出す。
    static final class InvalidRule extends RuntimeException {
        InvalidRule(String message) {
            super(message);
        }
    }

    // その番号の決まりがない（404）。
    static final class RuleNotFound extends RuntimeException {
        RuleNotFound() {
            super("その決まりは見つかりませんでした。");
        }
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return repository.list().stream().map(WritingRuleController::toMap).toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody RuleRequest request) {
        RuleRequest clean = cleanRequest(request);
        String source = request == null ? null : request.source();
        if (source == null || !WritingRuleRepository.SOURCES.contains(source)) {
            throw new InvalidRule("きっかけの種類は「本人」か「AIの指摘」を選んでください。");
        }
        long id = repository.add(clean.target(), clean.rule(), clean.reason(), source);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "追加しました");
        body.put("id", id);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody RuleRequest request) {
        RuleRequest clean = cleanRequest(request);
        if (!repository.update(id, clean.target(), clean.rule(), clean.reason())) {
            throw new RuleNotFound();
        }
        return Map.of("status", "直しました", "id", id);
    }

    @PutMapping("/{id}/active")
    public Map<String, Object> setActive(@PathVariable long id, @RequestBody ActiveRequest request) {
        if (request == null || request.active() == null) {
            throw new InvalidRule("有効にするか無効にするかを指定してください。");
        }
        if (!repository.setActive(id, request.active())) {
            throw new RuleNotFound();
        }
        return Map.of("status", request.active() ? "有効にしました" : "無効にしました", "id", id);
    }

    @ExceptionHandler(InvalidRule.class)
    ResponseEntity<Map<String, String>> invalid(InvalidRule e) {
        return failure(HttpStatus.BAD_REQUEST, "RULE_INVALID", e.getMessage());
    }

    @ExceptionHandler(RuleNotFound.class)
    ResponseEntity<Map<String, String>> notFound(RuleNotFound e) {
        return failure(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(WritingRuleRepository.Unavailable.class)
    ResponseEntity<Map<String, String>> unavailable(WritingRuleRepository.Unavailable e) {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "DB_UNAVAILABLE", e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> failure(HttpStatus status, String errorCode, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("errorCode", errorCode);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    // 対象・決まり・きっかけを確かめ、前後の空白を除いた形で返す（source はそのまま）。
    private static RuleRequest cleanRequest(RuleRequest request) {
        if (request == null) {
            throw new InvalidRule("決まりを入力してください。");
        }
        // List.of の contains は null で例外を投げるので、先に null を見る。
        if (request.target() == null || !WritingRuleRepository.TARGETS.contains(request.target())) {
            throw new InvalidRule("対象は「共通」「note記事」「SNS投稿」から選んでください。");
        }
        String rule = request.rule() == null ? "" : request.rule().strip();
        if (rule.isEmpty()) {
            throw new InvalidRule("決まりを入力してください。");
        }
        if (rule.length() > MAX_RULE_LENGTH) {
            throw new InvalidRule("決まりは" + MAX_RULE_LENGTH + "文字以内にしてください。");
        }
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new InvalidRule("きっかけは" + MAX_REASON_LENGTH + "文字以内にしてください。");
        }
        return new RuleRequest(request.target(), rule, reason, request.source());
    }

    private static Map<String, Object> toMap(WritingRuleRepository.Rule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rule.id());
        map.put("target", rule.target());
        map.put("rule", rule.rule());
        map.put("reason", rule.reason());
        map.put("source", rule.source());
        map.put("active", rule.active());
        return map;
    }

    public record RuleRequest(String target, String rule, String reason, String source) {
    }

    public record ActiveRequest(Boolean active) {
    }
}
