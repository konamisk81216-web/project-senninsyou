package com.senninsyou;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AiActivityRepository {

    // 中断したまま残った記録を、いつまでも作業中と表示しないための区切り。
    private static final int STALE_MINUTES = 10;

    public static final List<String> AGENTS = List.of(
            "AI将軍", "軍師AI", "取材AI", "ライターAI", "品質管理AI", "営業AI", "発信AI");

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public AiActivityRepository(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    // 記録は本来の処理の邪魔をしてはいけないので、失敗しても例外を投げない。
    public long start(String agent, String action) {
        String sql = "INSERT INTO ai_activities (agent, action) VALUES (?, ?) RETURNING id";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, agent);
            statement.setString(2, action);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getLong("id");
                }
            }
        } catch (Exception e) {
            System.out.println("稼働記録の開始に失敗しました。");
        }
        return -1;
    }

    public void finish(long id, String status, String detail) {
        if (id < 0) {
            return;
        }
        String sql = """
                UPDATE ai_activities
                SET status = ?, detail = ?, finished_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, status);
            statement.setString(2, detail == null ? "" : detail);
            statement.setLong(3, id);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("稼働記録の完了に失敗しました。");
        }
    }

    public List<Map<String, String>> recentActivities() {
        List<Map<String, String>> activities = new ArrayList<>();
        String sql = """
                SELECT agent, action, status, detail, started_at,
                       EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at)) AS age_seconds,
                       CASE WHEN finished_at IS NULL THEN NULL
                            ELSE ROUND(EXTRACT(EPOCH FROM (finished_at - started_at)))
                       END AS duration_seconds
                FROM ai_activities
                ORDER BY id DESC
                LIMIT 20
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            while (result.next()) {
                Map<String, String> activity = new LinkedHashMap<>();
                activity.put("agent", result.getString("agent"));
                activity.put("action", result.getString("action"));
                activity.put("status", displayStatus(
                        result.getString("status"), result.getDouble("age_seconds")));
                activity.put("detail", text(result.getString("detail")));
                activity.put("startedAt", String.valueOf(result.getTimestamp("started_at")));
                activity.put("durationSeconds", text(result.getString("duration_seconds")));
                activities.add(activity);
            }
        } catch (Exception e) {
            System.out.println("稼働記録の取得に失敗しました。");
        }
        return activities;
    }

    public List<Map<String, String>> agentStates(int waitingApprovals) {
        Map<String, Map<String, String>> latest = new LinkedHashMap<>();
        String sql = """
                SELECT DISTINCT ON (agent)
                       agent, action, status,
                       EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at)) AS age_seconds
                FROM ai_activities
                ORDER BY agent, id DESC
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            while (result.next()) {
                Map<String, String> state = new LinkedHashMap<>();
                state.put("action", text(result.getString("action")));
                state.put("status", displayStatus(
                        result.getString("status"), result.getDouble("age_seconds")));
                latest.put(result.getString("agent"), state);
            }
        } catch (Exception e) {
            System.out.println("AI社員の状態取得に失敗しました。");
        }

        List<Map<String, String>> states = new ArrayList<>();
        for (String agent : AGENTS) {
            Map<String, String> state = new LinkedHashMap<>();
            Map<String, String> found = latest.get(agent);
            state.put("agent", agent);
            state.put("lastAction", found == null ? "" : found.get("action"));

            String status = found == null ? "待機中" : found.get("status");
            if ("完了".equals(status)) {
                status = "待機中";
            }
            if ("発信AI".equals(agent) && waitingApprovals > 0 && "待機中".equals(status)) {
                status = "確認待ち";
            }
            state.put("status", status);
            states.add(state);
        }
        return states;
    }

    private String displayStatus(String status, double ageSeconds) {
        if ("作業中".equals(status) && ageSeconds > STALE_MINUTES * 60) {
            return "エラー";
        }
        if ("失敗".equals(status)) {
            return "エラー";
        }
        return status;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
