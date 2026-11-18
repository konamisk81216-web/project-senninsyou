package com.senninsyou;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// L-1の試験用の偽のDB。本番DBにはつながない。
// ・登録した機会・任務・収益記録の中から、SQLに渡された値（id・task_id）で行を選ぶ。
//   値を取り違えると別の行が返るので、別案件の混入を確かめられる。
// ・接続の設定・確定・取り消し・閉じる・SQLの実行を、events に順番どおり記録する（AIの呼び出しとの前後を確かめる）。
// ・failWhenSqlContains を含むSQLは、実行したときに失敗する。
final class NextMoveFakeDatabase {

    record OpportunityRow(long id, String title, int expectedRevenue, Integer taskId, String notes) {
    }

    record TaskRow(int id, String name, String status) {
    }

    record RevenueRow(long id, int taskId, LocalDate occurredOn, String description, int revenue, int expense, int workMinutes) {
    }

    final List<OpportunityRow> opportunities = new ArrayList<>();
    final List<TaskRow> tasks = new ArrayList<>();
    final List<RevenueRow> revenues = new ArrayList<>();
    final List<String> events;
    final List<String> executedSql = new ArrayList<>();
    String failWhenSqlContains;

    NextMoveFakeDatabase(List<String> events) {
        this.events = events;
    }

    Connection connection() {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit" -> record("db:autoCommit=" + args[0]);
                    case "setReadOnly" -> record("db:readOnly=" + args[0]);
                    case "setTransactionIsolation" -> record("db:isolation="
                            + ((int) args[0] == Connection.TRANSACTION_REPEATABLE_READ ? "REPEATABLE_READ" : args[0]));
                    case "commit" -> record("db:commit");
                    case "rollback" -> record("db:rollback");
                    case "close" -> record("db:close");
                    case "isClosed" -> false;
                    case "prepareStatement" -> statement((String) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Object record(String event) {
        events.add(event);
        return null;
    }

    private PreparedStatement statement(String sql) {
        Map<Integer, Object> params = new HashMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setLong", "setInt" -> {
                        params.put((Integer) args[0], args[1]);
                        yield null;
                    }
                    case "executeQuery" -> {
                        String name = queryName(sql);
                        events.add("db:query:" + name + params.values());
                        executedSql.add(sql);
                        if (failWhenSqlContains != null && sql.contains(failWhenSqlContains)) {
                            throw new SQLException("テスト用にわざと失敗させた読み取り: " + failWhenSqlContains);
                        }
                        yield resultSet(rows(sql, params));
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static String queryName(String sql) {
        if (sql.contains("FROM opportunities")) return "opportunity";
        if (sql.contains("FROM tasks")) return "task";
        if (sql.contains("COUNT(*)")) return "revenueTotal";
        return "revenueRecent";
    }

    private List<Map<String, Object>> rows(String sql, Map<Integer, Object> params) {
        long key = ((Number) params.get(1)).longValue();
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (queryName(sql)) {
            case "opportunity" -> opportunities.stream().filter(o -> o.id() == key).forEach(o -> {
                Map<String, Object> row = new HashMap<>();
                row.put("id", o.id());
                row.put("title", o.title());
                row.put("opportunity_type", "note記事");
                row.put("expected_revenue", BigDecimal.valueOf(o.expectedRevenue()));
                row.put("estimated_minutes", 60);
                row.put("risk_level", "中");
                row.put("status", "有望");
                row.put("notes", o.notes());
                row.put("task_id", o.taskId());
                rows.add(row);
            });
            case "task" -> tasks.stream().filter(t -> t.id() == key).forEach(t -> {
                Map<String, Object> row = new HashMap<>();
                row.put("id", t.id());
                row.put("task_name", t.name());
                row.put("status", t.status());
                row.put("priority", "高");
                row.put("assigned_agent", "偵察AI");
                rows.add(row);
            });
            case "revenueTotal" -> {
                List<RevenueRow> matched = revenues.stream().filter(r -> r.taskId() == key).toList();
                Map<String, Object> row = new HashMap<>();
                row.put("record_count", matched.size());
                // 本物のSQLと同じく、0件なら合計は NULL。
                row.put("total_revenue", matched.isEmpty() ? null : BigDecimal.valueOf(matched.stream().mapToInt(RevenueRow::revenue).sum()));
                row.put("total_expense", matched.isEmpty() ? null : BigDecimal.valueOf(matched.stream().mapToInt(RevenueRow::expense).sum()));
                row.put("total_profit", matched.isEmpty() ? null
                        : BigDecimal.valueOf(matched.stream().mapToInt(r -> r.revenue() - r.expense()).sum()));
                row.put("total_work_minutes", matched.isEmpty() ? null : matched.stream().mapToInt(RevenueRow::workMinutes).sum());
                rows.add(row);
            }
            default -> {
                int limit = ((Number) params.get(2)).intValue();
                revenues.stream().filter(r -> r.taskId() == key)
                        .sorted(Comparator.comparing(RevenueRow::occurredOn).thenComparing(RevenueRow::id).reversed())
                        .limit(limit)
                        .forEach(r -> {
                            Map<String, Object> row = new HashMap<>();
                            row.put("id", r.id());
                            row.put("occurred_on", Date.valueOf(r.occurredOn()));
                            row.put("description", r.description());
                            row.put("revenue", BigDecimal.valueOf(r.revenue()));
                            row.put("expense", BigDecimal.valueOf(r.expense()));
                            row.put("profit", BigDecimal.valueOf(r.revenue() - r.expense()));
                            row.put("work_minutes", r.workMinutes());
                            rows.add(row);
                        });
            }
        }
        return rows;
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        int[] index = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next" -> {
                            index[0]++;
                            return index[0] < rows.size();
                        }
                        case "close" -> {
                            return null;
                        }
                        default -> {
                        }
                    }
                    Object value = rows.get(index[0]).get((String) args[0]);
                    return switch (method.getName()) {
                        case "getString" -> value == null ? null : value.toString();
                        case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                        case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                        case "getBigDecimal" -> (BigDecimal) value;
                        case "getDate" -> (Date) value;
                        case "getObject" -> value == null ? null : ((Number) value).intValue();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
