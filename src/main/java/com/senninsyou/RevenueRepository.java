package com.senninsyou;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class RevenueRepository {

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

        return DriverManager.getConnection(
                jdbcUrl, credentials[0], credentials[1]);
    }

    public RevenueRecord add(
            Integer taskId,
            String description,
            BigDecimal revenue,
            BigDecimal expense,
            int workMinutes,
            LocalDate occurredOn,
            String notes) throws SQLException {

        String sql = """
                INSERT INTO revenue_records
                    (task_id, description, revenue, expense,
                     work_minutes, occurred_on, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """;

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            if (taskId == null) {
                statement.setNull(1, Types.INTEGER);
            } else {
                statement.setInt(1, taskId);
            }

            statement.setString(2, description);
            statement.setBigDecimal(3, revenue);
            statement.setBigDecimal(4, expense);
            statement.setInt(5, workMinutes);
            statement.setDate(6, Date.valueOf(occurredOn));
            statement.setString(7, notes);

            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return mapRecord(result);
            }
        }
    }

    public List<RevenueRecord> getAll() throws SQLException {
        String sql = """
                SELECT *
                FROM revenue_records
                ORDER BY occurred_on DESC, id DESC
                """;
        List<RevenueRecord> records = new ArrayList<>();

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                records.add(mapRecord(result));
            }
        }

        return records;
    }

    public RevenueSummary getSummary() throws SQLException {
        String sql = """
                SELECT
                    COALESCE(SUM(revenue), 0) AS total_revenue,
                    COALESCE(SUM(expense), 0) AS total_expense,
                    COALESCE(SUM(profit), 0) AS total_profit,
                    COALESCE(SUM(work_minutes), 0) AS total_work_minutes,
                    COUNT(*) AS record_count
                FROM revenue_records
                """;

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            result.next();
            return new RevenueSummary(
                    result.getBigDecimal("total_revenue"),
                    result.getBigDecimal("total_expense"),
                    result.getBigDecimal("total_profit"),
                    result.getInt("total_work_minutes"),
                    result.getInt("record_count"));
        }
    }

    public boolean delete(long id) throws SQLException {
        String sql = "DELETE FROM revenue_records WHERE id = ?";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        }
    }

    private RevenueRecord mapRecord(ResultSet result) throws SQLException {
        Integer taskId = result.getObject("task_id", Integer.class);

        return new RevenueRecord(
                result.getLong("id"),
                taskId,
                result.getString("description"),
                result.getBigDecimal("revenue"),
                result.getBigDecimal("expense"),
                result.getBigDecimal("profit"),
                result.getInt("work_minutes"),
                result.getDate("occurred_on").toLocalDate(),
                result.getString("notes"),
                result.getObject("created_at", java.time.OffsetDateTime.class));
    }
}
