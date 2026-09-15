package com.senninsyou;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class OpportunityRepository {

    private Connection getConnection() throws SQLException {
        String databaseUrl = System.getenv("DATABASE_URL");

        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URLが設定されていません。");
        }

        URI dbUri = URI.create(databaseUrl);
        String userInfo = dbUri.getUserInfo();

        if (userInfo == null || !userInfo.contains(":")) {
            throw new IllegalStateException("DATABASE_URLの形式が正しくありません。");
        }

        String[] credentials = userInfo.split(":", 2);
        int port = dbUri.getPort() == -1 ? 5432 : dbUri.getPort();
        String jdbcUrl = "jdbc:postgresql://" + dbUri.getHost() + ":" + port
                + dbUri.getPath() + "?sslmode=require";

        return DriverManager.getConnection(jdbcUrl, credentials[0], credentials[1]);
    }

    public Opportunity add(
            String title,
            String opportunityType,
            BigDecimal expectedRevenue,
            int estimatedMinutes,
            String riskLevel,
            String notes) throws SQLException {
        String sql = """
                INSERT INTO opportunities
                    (title, opportunity_type, expected_revenue,
                     estimated_minutes, risk_level, notes)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """;

        long opportunityId;

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, opportunityType);
            statement.setBigDecimal(3, expectedRevenue);
            statement.setInt(4, estimatedMinutes);
            statement.setString(5, riskLevel);
            statement.setString(6, notes);

            try (ResultSet result = statement.executeQuery()) {
                result.next();
                opportunityId = result.getLong("id");
            }
        }

        return getById(opportunityId);
    }

    public List<Opportunity> getAll() throws SQLException {
        String sql = """
                SELECT
                    o.*,
                    t.task_name AS linked_task_name,
                    t.status AS linked_task_status,
                    COALESCE(SUM(r.revenue), 0) AS actual_revenue,
                    COALESCE(SUM(r.expense), 0) AS actual_expense,
                    COALESCE(SUM(r.profit), 0) AS actual_profit
                FROM opportunities o
                LEFT JOIN tasks t ON t.id = o.task_id
                LEFT JOIN revenue_records r ON r.task_id = o.task_id
                GROUP BY o.id, t.task_name, t.status
                ORDER BY
                    CASE o.status
                        WHEN '有望' THEN 1
                        WHEN '調査中' THEN 2
                        WHEN '未評価' THEN 3
                        WHEN '保留' THEN 4
                        ELSE 5
                    END,
                    o.created_at DESC
                """;
        List<Opportunity> opportunities = new ArrayList<>();

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                opportunities.add(mapOpportunity(result));
            }
        }

        opportunities.sort((left, right) ->
                Integer.compare(right.comparisonScore(), left.comparisonScore()));
        return opportunities;
    }

    public Opportunity createLinkedTask(long opportunityId) throws SQLException {
        String selectSql = """
                SELECT title, status, task_id
                FROM opportunities
                WHERE id = ?
                FOR UPDATE
                """;
        String insertTaskSql = """
                INSERT INTO tasks (task_name, priority, assigned_agent)
                VALUES (?, '高', '偵察AI')
                RETURNING id
                """;
        String linkSql = "UPDATE opportunities SET task_id = ? WHERE id = ?";

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                String title;
                try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                    select.setLong(1, opportunityId);
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return null;
                        }
                        if (result.getObject("task_id") != null) {
                            throw new IllegalArgumentException("この収益機会は任務登録済みです。");
                        }
                        if (!"有望".equals(result.getString("status"))) {
                            throw new IllegalArgumentException("有望な収益機会だけ任務化できます。");
                        }
                        title = result.getString("title");
                    }
                }

                int taskId;
                try (PreparedStatement insert = connection.prepareStatement(insertTaskSql)) {
                    insert.setString(1, title);
                    try (ResultSet result = insert.executeQuery()) {
                        result.next();
                        taskId = result.getInt("id");
                    }
                }

                try (PreparedStatement link = connection.prepareStatement(linkSql)) {
                    link.setInt(1, taskId);
                    link.setLong(2, opportunityId);
                    link.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }

        return getById(opportunityId);
    }

    private Opportunity getById(long id) throws SQLException {
        String sql = """
                SELECT
                    o.*,
                    t.task_name AS linked_task_name,
                    t.status AS linked_task_status,
                    COALESCE(SUM(r.revenue), 0) AS actual_revenue,
                    COALESCE(SUM(r.expense), 0) AS actual_expense,
                    COALESCE(SUM(r.profit), 0) AS actual_profit
                FROM opportunities o
                LEFT JOIN tasks t ON t.id = o.task_id
                LEFT JOIN revenue_records r ON r.task_id = o.task_id
                WHERE o.id = ?
                GROUP BY o.id, t.task_name, t.status
                """;
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapOpportunity(result) : null;
            }
        }
    }

    public boolean updateStatus(long id, String status) throws SQLException {
        String sql = "UPDATE opportunities SET status = ? WHERE id = ?";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setLong(2, id);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean delete(long id) throws SQLException {
        String sql = "DELETE FROM opportunities WHERE id = ?";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    private Opportunity mapOpportunity(ResultSet result) throws SQLException {
        BigDecimal expectedRevenue = result.getBigDecimal("expected_revenue");
        int estimatedMinutes = result.getInt("estimated_minutes");
        String riskLevel = result.getString("risk_level");
        BigDecimal actualRevenue = getBigDecimalOrZero(result, "actual_revenue");
        BigDecimal actualExpense = getBigDecimalOrZero(result, "actual_expense");
        BigDecimal actualProfit = getBigDecimalOrZero(result, "actual_profit");
        BigDecimal hourlyRevenue = estimatedMinutes == 0 ? null
                : expectedRevenue.multiply(BigDecimal.valueOf(60))
                        .divide(BigDecimal.valueOf(estimatedMinutes), 0, RoundingMode.HALF_UP);

        return new Opportunity(
                result.getLong("id"),
                result.getString("title"),
                result.getString("opportunity_type"),
                expectedRevenue,
                estimatedMinutes,
                riskLevel,
                result.getString("status"),
                result.getString("notes"),
                result.getObject("task_id", Integer.class),
                getOptionalString(result, "linked_task_name"),
                getOptionalString(result, "linked_task_status"),
                actualRevenue,
                actualExpense,
                actualProfit,
                actualRevenue.subtract(expectedRevenue),
                hourlyRevenue,
                comparisonScore(hourlyRevenue, riskLevel),
                result.getObject("created_at", java.time.OffsetDateTime.class));
    }

    private BigDecimal getBigDecimalOrZero(ResultSet result, String column) throws SQLException {
        try {
            BigDecimal value = result.getBigDecimal(column);
            return value == null ? BigDecimal.ZERO : value;
        } catch (SQLException e) {
            return BigDecimal.ZERO;
        }
    }

    private String getOptionalString(ResultSet result, String column) throws SQLException {
        try {
            return result.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    private int comparisonScore(BigDecimal hourlyRevenue, String riskLevel) {
        if (hourlyRevenue == null) {
            return 0;
        }

        BigDecimal riskWeight = switch (riskLevel) {
            case "低" -> BigDecimal.ONE;
            case "高" -> BigDecimal.valueOf(0.4);
            default -> BigDecimal.valueOf(0.7);
        };

        return hourlyRevenue.multiply(riskWeight)
                .min(BigDecimal.valueOf(999999))
                .intValue();
    }
}
