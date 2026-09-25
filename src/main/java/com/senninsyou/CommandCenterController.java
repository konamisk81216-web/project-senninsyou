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
@RequestMapping("/api/command-center")
public class CommandCenterController {

    private final AiActivityRepository activityRepository;
    private final MetricsRepository metricsRepository;
    private final String jdbcUrl;
    private final String user;
    private final String password;

    public CommandCenterController() {
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
        metricsRepository = new MetricsRepository(jdbcUrl, user, password);
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("numbers", loadNumbers());
        status.put("priorityTasks", loadPriorityTasks());
        status.put("agents", activityRepository.agentStates(countWaitingApprovals()));
        status.put("activities", activityRepository.recentActivities());
        return status;
    }

    private Map<String, String> loadNumbers() {
        Map<String, String> numbers = new LinkedHashMap<>();
        numbers.put("revenue", "0");
        numbers.put("profit", "0");
        numbers.put("workMinutes", "0");
        numbers.put("aiCost", "0");

        String sql = """
                SELECT
                    COALESCE((SELECT SUM(revenue) FROM revenue_records), 0) AS revenue,
                    COALESCE((SELECT SUM(expense) FROM revenue_records), 0) AS expense,
                    COALESCE((SELECT SUM(work_minutes) FROM revenue_records), 0) AS work_minutes,
                    COALESCE((SELECT SUM(amount) FROM monthly_costs), 0) AS ai_cost
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            if (result.next()) {
                double revenue = result.getDouble("revenue");
                double expense = result.getDouble("expense");
                double aiCost = result.getDouble("ai_cost");
                numbers.put("revenue", String.valueOf(Math.round(revenue)));
                numbers.put("profit", String.valueOf(Math.round(revenue - expense - aiCost)));
                numbers.put("workMinutes", String.valueOf(result.getInt("work_minutes")));
                numbers.put("aiCost", String.valueOf(Math.round(aiCost)));
            }
        } catch (Exception e) {
            System.out.println("司令本部の数字を取得できませんでした。");
        }

        Map<String, String> summary = metricsRepository.getSummary();
        numbers.put("articleCount", summary.getOrDefault("articleCount", "0"));
        numbers.put("averageWritingSeconds", summary.getOrDefault("averageWritingSeconds", "0"));
        numbers.put("approvedPosts", summary.getOrDefault("approvedPosts", "0"));
        return numbers;
    }

    private List<Map<String, String>> loadPriorityTasks() {
        List<Map<String, String>> tasks = new ArrayList<>();
        String sql = """
                SELECT task_name, priority, assigned_agent, status
                FROM tasks
                WHERE status <> '完了'
                ORDER BY CASE priority WHEN '高' THEN 1 WHEN '中' THEN 2 ELSE 3 END, id
                LIMIT 3
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
            System.out.println("優先タスクを取得できませんでした。");
        }
        return tasks;
    }

    private int countWaitingApprovals() {
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
            System.out.println("承認待ちの件数を取得できませんでした。");
        }
        return 0;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
