package com.senninsyou;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/revenue")
public class RevenueController {

    private final RevenueRepository repository = new RevenueRepository();

    @PostMapping
    public ResponseEntity<?> add(@RequestBody RevenueRequest request) {
        String validationError = validate(request);

        if (validationError != null) {
            return ResponseEntity.badRequest().body(validationError);
        }

        try {
            RevenueRecord record = repository.add(
                    request.taskId(),
                    request.description().trim(),
                    valueOrZero(request.revenue()),
                    valueOrZero(request.expense()),
                    request.workMinutes() == null ? 0 : request.workMinutes(),
                    request.occurredOn() == null
                            ? LocalDate.now()
                            : request.occurredOn(),
                    request.notes());

            return ResponseEntity.created(
                    URI.create("/api/revenue/" + record.getId())).body(record);
        } catch (SQLException | IllegalStateException e) {
            System.out.println("収益記録の登録に失敗しました。");
            return ResponseEntity.internalServerError()
                    .body("収益記録の登録に失敗しました。");
        }
    }

    @GetMapping
    public ResponseEntity<?> getAll() {
        try {
            List<RevenueRecord> records = repository.getAll();
            return ResponseEntity.ok(records);
        } catch (SQLException | IllegalStateException e) {
            System.out.println("収益記録の取得に失敗しました。");
            return ResponseEntity.internalServerError()
                    .body("収益記録の取得に失敗しました。");
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<?> getSummary() {
        try {
            return ResponseEntity.ok(repository.getSummary());
        } catch (SQLException | IllegalStateException e) {
            System.out.println("収益集計の取得に失敗しました。");
            return ResponseEntity.internalServerError()
                    .body("収益集計の取得に失敗しました。");
        }
    }

    @GetMapping("/task-analysis")
    public ResponseEntity<?> getTaskAnalysis() {
        try {
            return ResponseEntity.ok(repository.getTaskAnalysis());
        } catch (SQLException | IllegalStateException e) {
            System.out.println("任務別収益分析の取得に失敗しました。");
            return ResponseEntity.internalServerError()
                    .body("任務別収益分析の取得に失敗しました。");
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable long id) {
        try {
            if (!repository.delete(id)) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok("収益記録を削除しました。");
        } catch (SQLException | IllegalStateException e) {
            System.out.println("収益記録の削除に失敗しました。");
            return ResponseEntity.internalServerError()
                    .body("収益記録の削除に失敗しました。");
        }
    }

    private String validate(RevenueRequest request) {
        if (request.description() == null
                || request.description().isBlank()) {
            return "説明を入力してください。";
        }

        if (valueOrZero(request.revenue()).signum() < 0
                || valueOrZero(request.expense()).signum() < 0) {
            return "売上と経費には0以上の金額を指定してください。";
        }

        if (request.workMinutes() != null && request.workMinutes() < 0) {
            return "作業時間には0以上の値を指定してください。";
        }

        return null;
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record RevenueRequest(
            Integer taskId,
            String description,
            BigDecimal revenue,
            BigDecimal expense,
            Integer workMinutes,
            LocalDate occurredOn,
            String notes) {
    }
}
