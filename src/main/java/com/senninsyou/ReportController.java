package com.senninsyou;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/report")
public class ReportController {

    private static final String NOT_AVAILABLE = "取得できません";
    private static final String NOT_REGISTERED = "未登録";

    private final AiActivityRepository activityRepository;

    public ReportController() {
        this(newActivityRepository());
    }

    // テストから、DBにつながない部品を差し込めるようにする。
    ReportController(AiActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    private static AiActivityRepository newActivityRepository() {
        Database.Settings settings = Database.settings();
        return new AiActivityRepository(settings.jdbcUrl(), settings.user(), settings.password());
    }

    @GetMapping
    public Map<String, Object> report() {
        try (Connection connection = Database.connect()) {
            return report(connection);
        } catch (Exception e) {
            // 接続できないときは、件数を0や「なし」に見せない。画面は全項目を「取得できません」と出す。
            System.out.println("報告を作れませんでした。");
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("available", false);
            report.put("changed", true);
            return report;
        }
    }

    // 読み取りが途中で1つ失敗しても、その項目だけ null（画面では「取得できません」）にする。
    // 失敗を「0件」「エラーなし」「未登録」と見せないため。本当に0件・未登録のときだけそう出す。
    Map<String, Object> report(Connection connection) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("available", true);

        List<Map<String, String>> tasks = queryTasks(connection);
        Integer waiting = countWaitingPosts(connection);
        report.put("priorityTasks", tasks);
        report.put("unfinishedCount", countUnfinishedTasks(connection));
        report.put("agents", activityRepository.agentStates(connection, waiting));
        report.put("waitingApprovals", waiting);
        report.put("recentErrors", queryRecentErrors(connection));
        report.put("lastCompleted", queryLastCompleted(connection));
        report.put("nextAction", tasks == null ? NOT_AVAILABLE
                : tasks.isEmpty() ? NOT_REGISTERED : tasks.get(0).get("taskName"));

        // 外部サービスの利用量は連携していないため、推測せず取得できないと伝える。
        Map<String, String> usage = new LinkedHashMap<>();
        usage.put("azure", NOT_AVAILABLE);
        usage.put("openai", NOT_AVAILABLE);
        usage.put("note", "AzureとOpenAIの管理画面と連携していないため、利用量は取得できません。");
        report.put("usage", usage);

        String signature = buildSignature(report);
        report.put("changed", hasChanged(connection, signature));
        report.put("lastReportedAt", loadLastReportedAt(connection));
        report.put("signature", signature);
        return report;
    }

    @PostMapping("/seen")
    public Map<String, String> markSeen(@RequestBody SeenRequest request) {
        String sql = """
                UPDATE report_state
                SET last_reported_at = CURRENT_TIMESTAMP, last_signature = ?
                WHERE id = 1
                """;

        try (
            Connection connection = Database.connect();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, request.signature() == null ? "" : request.signature());
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("報告状態の保存に失敗しました。");
        }
        return Map.of("status", "記録しました");
    }

    // 画面に出す上位5件。件数は countUnfinishedTasks で別に数える（5件で頭打ちにしない）。
    private List<Map<String, String>> queryTasks(Connection connection) {
        List<Map<String, String>> tasks = new ArrayList<>();
        String sql = """
                SELECT task_name, priority, assigned_agent, status
                FROM tasks
                WHERE status <> '完了'
                ORDER BY CASE priority WHEN '高' THEN 1 WHEN '中' THEN 2 ELSE 3 END, id
                LIMIT 5
                """;

        try (
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            while (result.next()) {
                Map<String, String> task = new LinkedHashMap<>();
                task.put("taskName", result.getString("task_name"));
                task.put("priority", result.getString("priority"));
                task.put("assignedAgent", text(result.getString("assigned_agent")));
                task.put("status", result.getString("status"));
                tasks.add(task);
            }
        } catch (Exception e) {
            System.out.println("タスクを取得できませんでした。");
            return null;
        }
        return tasks;
    }

    private Integer countUnfinishedTasks(Connection connection) {
        return count(connection,
                "SELECT COUNT(*) AS n FROM tasks WHERE status <> '完了'",
                "未完了タスクの件数を取得できませんでした。");
    }

    private Integer countWaitingPosts(Connection connection) {
        return count(connection,
                "SELECT COUNT(*) AS n FROM post_drafts WHERE status = '承認待ち'",
                "承認待ちを取得できませんでした。");
    }

    // 件数を数える。取得に失敗したときは null（0件と区別する）。
    private Integer count(Connection connection, String sql, String failureMessage) {
        try (
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            if (result.next()) {
                return result.getInt("n");
            }
        } catch (Exception e) {
            System.out.println(failureMessage);
        }
        return null;
    }

    private List<Map<String, String>> queryRecentErrors(Connection connection) {
        List<Map<String, String>> errors = new ArrayList<>();
        String sql = """
                SELECT agent, action, detail, started_at
                FROM ai_activities
                WHERE status = '失敗'
                ORDER BY id DESC
                LIMIT 3
                """;

        try (
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            while (result.next()) {
                Map<String, String> error = new LinkedHashMap<>();
                error.put("agent", result.getString("agent"));
                error.put("action", result.getString("action"));
                error.put("detail", text(result.getString("detail")));
                error.put("startedAt", String.valueOf(result.getTimestamp("started_at")));
                errors.add(error);
            }
        } catch (Exception e) {
            // 失敗を「直近のエラー：なし」と見せないよう、null を返す。
            System.out.println("エラー履歴を取得できませんでした。");
            return null;
        }
        return errors;
    }

    // 完了記録が1件もないときは「未登録」、取得に失敗したときは null。
    private Map<String, String> queryLastCompleted(Connection connection) {
        Map<String, String> last = new LinkedHashMap<>();
        String sql = """
                SELECT agent, action, detail, finished_at
                FROM ai_activities
                WHERE status = '完了'
                ORDER BY id DESC
                LIMIT 1
                """;

        try (
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            if (result.next()) {
                last.put("agent", result.getString("agent"));
                last.put("action", result.getString("action"));
                last.put("detail", text(result.getString("detail")));
                last.put("finishedAt", String.valueOf(result.getTimestamp("finished_at")));
                return last;
            }
        } catch (Exception e) {
            System.out.println("完了記録を取得できませんでした。");
            return null;
        }
        last.put("agent", NOT_REGISTERED);
        last.put("action", NOT_REGISTERED);
        last.put("detail", "");
        last.put("finishedAt", "");
        return last;
    }

    // 前回から変わったかを見分けるための目印。
    // ・件数は表示用の上位数件ではなく、DB上の実件数を使う（7件→6件で上位5件が同じでも気づけるように）。
    // ・一覧は件数だけでなく中身も見る（同じ件数のまま入れ替わった・内容が変わったときも気づけるように）。
    //   中身はそのまま並べると長いので、短い要約値（ハッシュ）にする。
    // ・取れなかった項目は「?」にする（失敗したこと自体を変化として報告できるように）。
    @SuppressWarnings("unchecked")
    String buildSignature(Map<String, Object> report) {
        List<Map<String, String>> tasks = (List<Map<String, String>>) report.get("priorityTasks");
        Map<String, String> last = (Map<String, String>) report.get("lastCompleted");
        List<Map<String, String>> errors = (List<Map<String, String>>) report.get("recentErrors");

        return "v2"
                + "/unfinished=" + orUnknown(report.get("unfinishedCount"))
                + "/tasks=" + digest(tasks, "taskName", "priority", "status", "assignedAgent")
                + "/waiting=" + orUnknown(report.get("waitingApprovals"))
                + "/errors=" + digest(errors, "agent", "action", "detail", "startedAt")
                + "/last=" + (last == null ? "?" : last.get("finishedAt") + "|" + last.get("action"));
    }

    private static String orUnknown(Object value) {
        return value == null ? "?" : String.valueOf(value);
    }

    // 一覧の並び順と中身を、短い要約値にする。一覧が取れなかったときは「?」。
    private static String digest(List<Map<String, String>> rows, String... keys) {
        if (rows == null) {
            return "?";
        }
        StringBuilder text = new StringBuilder();
        for (Map<String, String> row : rows) {
            for (String key : keys) {
                text.append(row.get(key)).append('\u001f');
            }
            text.append('\u001e');
        }
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return rows.size() + ":" + java.util.HexFormat.of().formatHex(hash, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が使えません。", e);
        }
    }

    private boolean hasChanged(Connection connection, String signature) {
        String sql = "SELECT last_signature FROM report_state WHERE id = 1";

        try (
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            if (result.next()) {
                String saved = result.getString("last_signature");
                return saved == null || !saved.equals(signature);
            }
        } catch (Exception e) {
            System.out.println("前回の報告内容を取得できませんでした。");
        }
        return true;
    }

    // 一度も確認していないときは「未登録」、取得に失敗したときは「取得できません」。
    private String loadLastReportedAt(Connection connection) {
        String sql = "SELECT last_reported_at FROM report_state WHERE id = 1";

        try (
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            if (result.next() && result.getTimestamp("last_reported_at") != null) {
                return String.valueOf(result.getTimestamp("last_reported_at"));
            }
        } catch (Exception e) {
            System.out.println("最終報告日時を取得できませんでした。");
            return NOT_AVAILABLE;
        }
        return NOT_REGISTERED;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record SeenRequest(String signature) {}
}
