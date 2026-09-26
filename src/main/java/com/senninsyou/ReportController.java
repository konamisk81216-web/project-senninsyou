package com.senninsyou;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
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
    private final String jdbcUrl;
    private final String user;
    private final String password;

    public ReportController() {
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

        jdbcUrl = "jdbc:postgresql://" + dbUri.getHost() + ":" + port + dbUri.getPath()
                + "?sslmode=require";
        user = credentials[0];
        password = credentials[1];
        activityRepository = new AiActivityRepository(jdbcUrl, user, password);
    }

    @GetMapping
    public Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();

        List<Map<String, String>> tasks = queryTasks();
        report.put("priorityTasks", tasks);
        report.put("unfinishedCount", tasks.size());
        report.put("agents", activityRepository.agentStates(countWaitingPosts()));
        report.put("waitingApprovals", countWaitingPosts());
        report.put("recentErrors", queryRecentErrors());
        report.put("lastCompleted", queryLastCompleted());
        report.put("nextAction", tasks.isEmpty() ? NOT_REGISTERED : tasks.get(0).get("taskName"));

        // 外部サービスの利用量は連携していないため、推測せず取得できないと伝える。
        Map<String, String> usage = new LinkedHashMap<>();
        usage.put("azure", NOT_AVAILABLE);
        usage.put("openai", NOT_AVAILABLE);
        usage.put("note", "AzureとOpenAIの管理画面と連携していないため、利用量は取得できません。");
        report.put("usage", usage);

        String signature = buildSignature(report);
        report.put("changed", hasChanged(signature));
        report.put("lastReportedAt", loadLastReportedAt());
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
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, request.signature() == null ? "" : request.signature());
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("報告状態の保存に失敗しました。");
        }
        return Map.of("status", "記録しました");
    }

    private List<Map<String, String>> queryTasks() {
        List<Map<String, String>> tasks = new ArrayList<>();
        String sql = """
                SELECT task_name, priority, assigned_agent, status
                FROM tasks
                WHERE status <> '完了'
                ORDER BY CASE priority WHEN '高' THEN 1 WHEN '中' THEN 2 ELSE 3 END, id
                LIMIT 5
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
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
        }
        return tasks;
    }

    private List<Map<String, String>> queryRecentErrors() {
        List<Map<String, String>> errors = new ArrayList<>();
        String sql = """
                SELECT agent, action, detail, started_at
                FROM ai_activities
                WHERE status = '失敗'
                ORDER BY id DESC
                LIMIT 3
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
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
            System.out.println("エラー履歴を取得できませんでした。");
        }
        return errors;
    }

    private Map<String, String> queryLastCompleted() {
        Map<String, String> last = new LinkedHashMap<>();
        String sql = """
                SELECT agent, action, detail, finished_at
                FROM ai_activities
                WHERE status = '完了'
                ORDER BY id DESC
                LIMIT 1
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
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
        }
        last.put("agent", NOT_REGISTERED);
        last.put("action", NOT_REGISTERED);
        last.put("detail", "");
        last.put("finishedAt", "");
        return last;
    }

    private int countWaitingPosts() {
        String sql = "SELECT COUNT(*) AS waiting FROM post_drafts WHERE status = '承認待ち'";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            if (result.next()) {
                return result.getInt("waiting");
            }
        } catch (Exception e) {
            System.out.println("承認待ちを取得できませんでした。");
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private String buildSignature(Map<String, Object> report) {
        List<Map<String, String>> tasks = (List<Map<String, String>>) report.get("priorityTasks");
        Map<String, String> last = (Map<String, String>) report.get("lastCompleted");
        List<Map<String, String>> errors = (List<Map<String, String>>) report.get("recentErrors");

        return tasks.size() + "/" + report.get("waitingApprovals") + "/"
                + errors.size() + "/" + last.get("finishedAt") + "/" + last.get("action");
    }

    private boolean hasChanged(String signature) {
        String sql = "SELECT last_signature FROM report_state WHERE id = 1";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
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

    private String loadLastReportedAt() {
        String sql = "SELECT last_reported_at FROM report_state WHERE id = 1";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            if (result.next() && result.getTimestamp("last_reported_at") != null) {
                return String.valueOf(result.getTimestamp("last_reported_at"));
            }
        } catch (Exception e) {
            System.out.println("最終報告日時を取得できませんでした。");
        }
        return NOT_REGISTERED;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record SeenRequest(String signature) {}
}
