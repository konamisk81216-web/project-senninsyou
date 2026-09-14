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
                RETURNING *
                """;

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
                return mapOpportunity(result);
            }
        }
    }

    public List<Opportunity> getAll() throws SQLException {
        String sql = """
                SELECT *
                FROM opportunities
                ORDER BY
                    CASE status
                        WHEN '有望' THEN 1
                        WHEN '調査中' THEN 2
                        WHEN '未評価' THEN 3
                        WHEN '保留' THEN 4
                        ELSE 5
                    END,
                    created_at DESC
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
                hourlyRevenue,
                comparisonScore(hourlyRevenue, riskLevel),
                result.getObject("created_at", java.time.OffsetDateTime.class));
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
