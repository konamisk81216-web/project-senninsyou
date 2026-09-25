package com.senninsyou;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MetricsRepository {

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public MetricsRepository(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    public void addMetric(
            String title, String measuredOn, int views, int clicks, int purchases, String memo) {

        String sql = """
                INSERT INTO content_metrics (title, measured_on, views, clicks, purchases, memo)
                VALUES (?, CAST(? AS DATE), ?, ?, ?, ?)
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, title);
            statement.setString(2, measuredOn);
            statement.setInt(3, views);
            statement.setInt(4, clicks);
            statement.setInt(5, purchases);
            statement.setString(6, memo);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("実績の保存に失敗しました。");
            e.printStackTrace();
        }
    }

    public List<Map<String, String>> getMetrics() {
        List<Map<String, String>> metrics = new ArrayList<>();
        String sql = """
                SELECT *,
                       CASE WHEN views > 0
                            THEN ROUND(purchases::numeric * 100 / views, 1)
                            ELSE NULL END AS purchase_rate
                FROM content_metrics
                ORDER BY measured_on DESC, id DESC
                LIMIT 50
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            while (result.next()) {
                Map<String, String> metric = new LinkedHashMap<>();
                metric.put("id", String.valueOf(result.getLong("id")));
                metric.put("title", result.getString("title"));
                metric.put("measuredOn", String.valueOf(result.getDate("measured_on")));
                metric.put("views", String.valueOf(result.getInt("views")));
                metric.put("clicks", String.valueOf(result.getInt("clicks")));
                metric.put("purchases", String.valueOf(result.getInt("purchases")));
                String rate = result.getString("purchase_rate");
                metric.put("purchaseRate", rate == null ? "" : rate);
                metric.put("memo", result.getString("memo") == null ? "" : result.getString("memo"));
                metrics.add(metric);
            }
        } catch (Exception e) {
            System.out.println("実績の取得に失敗しました。");
            e.printStackTrace();
        }
        return metrics;
    }

    public void deleteMetric(long id) {
        execute("DELETE FROM content_metrics WHERE id = ?", id);
    }

    public void addCost(String month, String service, double amount) {
        String sql = "INSERT INTO monthly_costs (month, service, amount) VALUES (?, ?, ?)";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, month);
            statement.setString(2, service);
            statement.setDouble(3, amount);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("費用の保存に失敗しました。");
            e.printStackTrace();
        }
    }

    public List<Map<String, String>> getCosts() {
        List<Map<String, String>> costs = new ArrayList<>();
        String sql = "SELECT * FROM monthly_costs ORDER BY month DESC, id DESC LIMIT 30";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            while (result.next()) {
                Map<String, String> cost = new LinkedHashMap<>();
                cost.put("id", String.valueOf(result.getLong("id")));
                cost.put("month", result.getString("month"));
                cost.put("service", result.getString("service"));
                cost.put("amount", String.valueOf(result.getBigDecimal("amount")));
                costs.add(cost);
            }
        } catch (Exception e) {
            System.out.println("費用の取得に失敗しました。");
            e.printStackTrace();
        }
        return costs;
    }

    public void deleteCost(long id) {
        execute("DELETE FROM monthly_costs WHERE id = ?", id);
    }

    // 記事の作成時間と投稿数は、すでに記録されているデータから計算する。
    public Map<String, String> getSummary() {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("articleCount", "0");
        summary.put("averageWritingSeconds", "0");
        summary.put("approvedPosts", "0");
        summary.put("totalCost", "0");

        String sql = """
                SELECT
                    (SELECT COUNT(*) FROM writer_jobs WHERE status = '完了') AS article_count,
                    (SELECT COALESCE(ROUND(AVG(EXTRACT(EPOCH FROM (updated_at - created_at)))), 0)
                       FROM writer_jobs WHERE status = '完了') AS avg_seconds,
                    (SELECT COUNT(*) FROM post_drafts WHERE status = '承認済み') AS approved_posts,
                    (SELECT COALESCE(SUM(amount), 0) FROM monthly_costs) AS total_cost
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            if (result.next()) {
                summary.put("articleCount", String.valueOf(result.getInt("article_count")));
                summary.put("averageWritingSeconds", String.valueOf(result.getInt("avg_seconds")));
                summary.put("approvedPosts", String.valueOf(result.getInt("approved_posts")));
                summary.put("totalCost", String.valueOf(result.getBigDecimal("total_cost")));
            }
        } catch (Exception e) {
            System.out.println("実績の集計に失敗しました。");
            e.printStackTrace();
        }
        return summary;
    }

    private void execute(String sql, long id) {
        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("削除に失敗しました。");
            e.printStackTrace();
        }
    }
}
