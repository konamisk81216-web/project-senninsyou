package com.senninsyou;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.SQLException;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/opportunities")
public class OpportunityController {

    private static final Set<String> RISK_LEVELS = Set.of("低", "中", "高");
    private static final Set<String> STATUSES =
            Set.of("未評価", "調査中", "有望", "保留", "却下");
    private final OpportunityRepository repository = new OpportunityRepository();

    @GetMapping
    public ResponseEntity<?> getAll() {
        try {
            return ResponseEntity.ok(repository.getAll());
        } catch (SQLException | IllegalStateException e) {
            return ResponseEntity.internalServerError().body("収益機会の取得に失敗しました。");
        }
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody OpportunityRequest request) {
        String error = validate(request);
        if (error != null) {
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Opportunity opportunity = repository.add(
                    request.title().trim(),
                    request.opportunityType().trim(),
                    valueOrZero(request.expectedRevenue()),
                    request.estimatedMinutes() == null ? 0 : request.estimatedMinutes(),
                    request.riskLevel(),
                    request.notes());
            return ResponseEntity.created(
                    URI.create("/api/opportunities/" + opportunity.id())).body(opportunity);
        } catch (SQLException | IllegalStateException e) {
            return ResponseEntity.internalServerError().body("収益機会の登録に失敗しました。");
        }
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<String> updateStatus(
            @PathVariable long id,
            @RequestBody StatusRequest request) {
        if (request.status() == null || !STATUSES.contains(request.status())) {
            return ResponseEntity.badRequest().body("状態の値が正しくありません。");
        }

        try {
            if (!repository.updateStatus(id, request.status())) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok("状態を更新しました。");
        } catch (SQLException | IllegalStateException e) {
            return ResponseEntity.internalServerError().body("状態の更新に失敗しました。");
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable long id) {
        try {
            if (!repository.delete(id)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok("収益機会を削除しました。");
        } catch (SQLException | IllegalStateException e) {
            return ResponseEntity.internalServerError().body("収益機会の削除に失敗しました。");
        }
    }

    private String validate(OpportunityRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            return "機会名を入力してください。";
        }
        if (request.opportunityType() == null || request.opportunityType().isBlank()) {
            return "種別を入力してください。";
        }
        if (valueOrZero(request.expectedRevenue()).signum() < 0) {
            return "想定収益には0以上の金額を指定してください。";
        }
        if (request.estimatedMinutes() != null && request.estimatedMinutes() < 0) {
            return "必要時間には0以上の値を指定してください。";
        }
        if (request.riskLevel() == null || !RISK_LEVELS.contains(request.riskLevel())) {
            return "リスクの値が正しくありません。";
        }
        return null;
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record OpportunityRequest(
            String title,
            String opportunityType,
            BigDecimal expectedRevenue,
            Integer estimatedMinutes,
            String riskLevel,
            String notes) {
    }

    public record StatusRequest(String status) {
    }
}
