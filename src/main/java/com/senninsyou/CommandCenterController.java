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
@RequestMapping("/api/command-center")
public class CommandCenterController {

    private final AiActivityRepository activityRepository;
    private final MetricsRepository metricsRepository;
    private final RevenueRepository revenueRepository;

    public CommandCenterController() {
        this(newActivityRepository(), newMetricsRepository(), new RevenueRepository());
    }

    // テストから、DBにつながない部品を差し込めるようにする。
    CommandCenterController(
            AiActivityRepository activityRepository,
            MetricsRepository metricsRepository,
            RevenueRepository revenueRepository) {
        this.activityRepository = activityRepository;
        this.metricsRepository = metricsRepository;
        this.revenueRepository = revenueRepository;
    }

    private static AiActivityRepository newActivityRepository() {
        Database.Settings settings = Database.settings();
        return new AiActivityRepository(settings.jdbcUrl(), settings.user(), settings.password());
    }

    private static MetricsRepository newMetricsRepository() {
        Database.Settings settings = Database.settings();
        return new MetricsRepository(settings.jdbcUrl(), settings.user(), settings.password());
    }

    // 画面が5秒ごとに呼ぶ。接続を読み取りごとに開くと1回で約5秒かかっていたので、
    // 接続は1回だけ開いて、すべての読み取りに使い回す。
    @GetMapping
    public Map<String, Object> status() {
        try (Connection connection = Database.connect()) {
            return status(connection);
        } catch (Exception e) {
            // 接続できないときは、数字を0や「未記録」に見せず、画面で「取得できません」と出す。
            System.out.println("司令本部のデータを取得できませんでした。");
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("money", null);
            status.put("numbers", null);
            status.put("priorityTasks", null);
            status.put("agents", null);
            status.put("activities", null);
            return status;
        }
    }

    // 読み取りが途中で1つ失敗しても、その項目だけ null（画面では「取得できません」）にして、
    // ほかの項目は出す。失敗を「0本」「タスクなし」などの実績ゼロと見せないため。
    Map<String, Object> status(Connection connection) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("money", loadMoney(connection));
        status.put("numbers", metricsRepository.getSummary(connection));
        status.put("priorityTasks", loadPriorityTasks(connection));
        status.put("agents", activityRepository.agentStates(connection, countWaitingApprovals(connection)));
        status.put("activities", activityRepository.recentActivities(connection));
        return status;
    }

    // 売上・直接経費・案件利益・作業時間・月々の費用。集計は RevenueRepository.getSummary の1か所だけ。
    private RevenueSummary loadMoney(Connection connection) {
        try {
            return revenueRepository.getSummary(connection);
        } catch (Exception e) {
            System.out.println("司令本部の数字を取得できませんでした。");
            return null;
        }
    }

    private List<Map<String, String>> loadPriorityTasks(Connection connection) {
        List<Map<String, String>> tasks = new ArrayList<>();
        String sql = """
                SELECT task_name, priority, assigned_agent, status
                FROM tasks
                WHERE status <> '完了'
                ORDER BY CASE priority WHEN '高' THEN 1 WHEN '中' THEN 2 ELSE 3 END, id
                LIMIT 3
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
            System.out.println("優先タスクを取得できませんでした。");
            // 失敗を「未完了のタスクはありません」と見せないよう、null を返す。
            return null;
        }
        return tasks;
    }

    // 取得に失敗したときは null（0件と区別する）。
    private Integer countWaitingApprovals(Connection connection) {
        String sql = "SELECT COUNT(*) AS waiting FROM post_drafts WHERE status = '承認待ち'";

        try (
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            if (result.next()) {
                return result.getInt("waiting");
            }
        } catch (Exception e) {
            System.out.println("承認待ちの件数を取得できませんでした。");
        }
        return null;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
