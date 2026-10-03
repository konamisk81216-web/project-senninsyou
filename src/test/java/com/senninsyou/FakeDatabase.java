package com.senninsyou;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// テスト用の偽のDB接続。本番DBにはつながない。
// ・SQLの文面に failWhenSqlContains を含む読み取りだけを、わざと失敗させる。
// ・rowsBySql に登録したSQLの一部を含む読み取りは、登録した行を返す（列名→値。集計にも使える）。
// ・それ以外の件数や合計を出す集計（COUNT( / SUM( を含むSQL）は、値が countValue の1行を返す。
// ・それ以外の一覧を出す読み取りは listRows 行を返す（文字は空、数値は0）。
// ・SQLの LIMIT は見ない。行を登録するときは、本物のDBが返す行数（LIMIT後）を登録すること。
final class FakeDatabase {

    private FakeDatabase() {
    }

    // 本当に0件の状態（集計は0、一覧は0行）。
    static Connection connection(String failWhenSqlContains) {
        return connection(failWhenSqlContains, 0, 0, Map.of());
    }

    static Connection connection(String failWhenSqlContains, int countValue, int listRows) {
        return connection(failWhenSqlContains, countValue, listRows, Map.of());
    }

    static Connection connection(
            String failWhenSqlContains, int countValue, int listRows,
            Map<String, List<Map<String, Object>>> rowsBySql) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        if (failWhenSqlContains != null && sql.contains(failWhenSqlContains)) {
                            throw new SQLException("テスト用にわざと失敗させた読み取り: " + failWhenSqlContains);
                        }
                        boolean aggregate = sql.contains("COUNT(") || sql.contains("SUM(");
                        yield statement(rowsFor(sql, listRows, rowsBySql), aggregate ? countValue : 0);
                    }
                    case "close" -> null;
                    case "isClosed" -> false;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    // 1行分の列名→値を、並べた順に作る。例: row("task_name", "A", "priority", "高")
    static Map<String, Object> row(Object... namesAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            row.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return row;
    }

    private static List<Map<String, Object>> rowsFor(
            String sql, int listRows, Map<String, List<Map<String, Object>>> rowsBySql) {
        for (Map.Entry<String, List<Map<String, Object>>> entry : rowsBySql.entrySet()) {
            if (sql.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        if (sql.contains("COUNT(") || sql.contains("SUM(")) {
            return List.of(Map.of());   // 集計は1行。数値は statement に渡す既定値（countValue）で返す
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < listRows; i++) {
            rows.add(Map.of());
        }
        return rows;
    }

    private static PreparedStatement statement(List<Map<String, Object>> rows, int defaultNumber) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> resultSet(rows, defaultNumber);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    // 登録された行を返す。登録のない列は、文字なら空、数値なら defaultNumber（集計は countValue、一覧は0）。
    private static ResultSet resultSet(List<Map<String, Object>> rows, int defaultNumber) {
        int[] index = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("next")) {
                        index[0]++;
                        return index[0] < rows.size();
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    Object value = rows.get(index[0]).get((String) args[0]);
                    return switch (method.getName()) {
                        case "getString" -> value == null ? "" : value.toString();
                        case "getTimestamp" -> value instanceof Timestamp timestamp ? timestamp : null;
                        case "getBigDecimal" -> value == null ? BigDecimal.valueOf(defaultNumber) : new BigDecimal(value.toString());
                        case "getInt" -> value == null ? defaultNumber : ((Number) value).intValue();
                        case "getLong" -> value == null ? (long) defaultNumber : ((Number) value).longValue();
                        case "getDouble" -> value == null ? (double) defaultNumber : ((Number) value).doubleValue();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
