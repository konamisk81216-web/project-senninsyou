package com.senninsyou;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// L-1の根拠を読む。読み取りだけで、DBには書き込まない。
// ・1本の接続で、読み取り専用・REPEATABLE READ の1つのトランザクションを開き、機会・任務・収益記録を読む。
//   PostgreSQL の REPEATABLE READ では、トランザクションの中の読み取りはすべて同じ時点のデータを見る。
// ・収益記録は「関連任務の task_id と同じ行」だけを、値をパラメータで渡して読む。
// ・読み終えたら確定（commit）する。失敗したら取り消して、そのまま呼び出し元へ失敗として返す（推測で埋めない）。
// 接続を閉じるのは呼び出し元（AIを呼ぶ前に閉じる）。
final class NextMoveEvidenceReader {

    static final String OPPORTUNITY_SQL = """
            SELECT id, title, opportunity_type, expected_revenue, estimated_minutes,
                   risk_level, status, notes, task_id
            FROM opportunities
            WHERE id = ?
            """;
    static final String TASK_SQL = """
            SELECT id, task_name, status, priority, assigned_agent
            FROM tasks
            WHERE id = ?
            """;
    static final String REVENUE_TOTAL_SQL = """
            SELECT COUNT(*) AS record_count,
                   SUM(revenue) AS total_revenue,
                   SUM(expense) AS total_expense,
                   SUM(profit) AS total_profit,
                   SUM(work_minutes) AS total_work_minutes
            FROM revenue_records
            WHERE task_id = ?
            """;
    static final String REVENUE_RECENT_SQL = """
            SELECT id, occurred_on, description, revenue, expense, profit, work_minutes
            FROM revenue_records
            WHERE task_id = ?
            ORDER BY occurred_on DESC, id DESC
            LIMIT ?
            """;

    // 機会が存在しなければ null。読み取りに失敗したら SQLException（0件や未登録には置き換えない）。
    NextMoveEvidence read(Connection connection, long opportunityId) throws SQLException {
        connection.setAutoCommit(false);
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try {
            NextMoveEvidence evidence = readInTransaction(connection, opportunityId);
            connection.commit();
            return evidence;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
    }

    private NextMoveEvidence readInTransaction(Connection connection, long opportunityId) throws SQLException {
        NextMoveEvidence.OpportunityPart opportunity = readOpportunity(connection, opportunityId);
        if (opportunity == null) {
            return null;
        }
        if (opportunity.taskId() == null) {
            return new NextMoveEvidence(opportunity, null, null);
        }

        NextMoveEvidence.TaskPart task = readTask(connection, opportunity.taskId());
        if (task == null) {
            // 機会は任務に結び付いているのに、その任務が読めない。任務未登録とは扱わず、取得失敗にする。
            throw new SQLException("関連任務を読み取れませんでした。");
        }
        NextMoveEvidence.RevenuePart revenue = readRevenue(connection, task.id());
        return new NextMoveEvidence(opportunity, task, revenue);
    }

    private NextMoveEvidence.OpportunityPart readOpportunity(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(OPPORTUNITY_SQL)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new NextMoveEvidence.OpportunityPart(
                        result.getLong("id"),
                        result.getString("title"),
                        result.getString("opportunity_type"),
                        result.getBigDecimal("expected_revenue"),
                        result.getInt("estimated_minutes"),
                        result.getString("risk_level"),
                        result.getString("status"),
                        result.getString("notes"),
                        result.getObject("task_id", Integer.class));
            }
        }
    }

    private NextMoveEvidence.TaskPart readTask(Connection connection, int taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TASK_SQL)) {
            statement.setInt(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new NextMoveEvidence.TaskPart(
                        result.getInt("id"),
                        result.getString("task_name"),
                        result.getString("status"),
                        result.getString("priority"),
                        result.getString("assigned_agent"));
            }
        }
    }

    private NextMoveEvidence.RevenuePart readRevenue(Connection connection, int taskId) throws SQLException {
        int count;
        BigDecimal totalRevenue;
        BigDecimal totalExpense;
        BigDecimal totalProfit;
        Integer totalWorkMinutes;
        try (PreparedStatement statement = connection.prepareStatement(REVENUE_TOTAL_SQL)) {
            statement.setInt(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("収益記録の件数を読み取れませんでした。");
                }
                count = result.getInt("record_count");
                boolean recorded = count > 0;
                // 記録0件なら合計は読まない（SQLでは NULL。0円に置き換えない）。
                totalRevenue = recorded ? required(result.getBigDecimal("total_revenue"), "売上合計") : null;
                totalExpense = recorded ? required(result.getBigDecimal("total_expense"), "直接経費合計") : null;
                totalProfit = recorded ? required(result.getBigDecimal("total_profit"), "案件利益合計") : null;
                // SUM(work_minutes) は PostgreSQL では bigint なので Long で読み、Integer に収まるときだけ変換する。
                totalWorkMinutes = recorded
                        ? toInteger(required(result.getObject("total_work_minutes", Long.class), "作業時間合計"), "作業時間合計") : null;
            }
        }

        List<NextMoveEvidence.RecordPart> recent = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(REVENUE_RECENT_SQL)) {
            statement.setInt(1, taskId);
            statement.setInt(2, NextMoveEvidence.RECENT_RECORD_LIMIT);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Date occurredOn = result.getDate("occurred_on");
                    recent.add(new NextMoveEvidence.RecordPart(
                            result.getLong("id"),
                            occurredOn == null ? null : occurredOn.toLocalDate(),
                            result.getString("description"),
                            result.getBigDecimal("revenue"),
                            result.getBigDecimal("expense"),
                            result.getBigDecimal("profit"),
                            result.getInt("work_minutes")));
                }
            }
        }

        // 同じ時点のデータなら、明細は件数以下で、記録があれば1件以上ある。合わなければ推測せず失敗にする。
        int expectedRecent = Math.min(count, NextMoveEvidence.RECENT_RECORD_LIMIT);
        if (recent.size() != expectedRecent) {
            throw new SQLException("収益記録の件数と明細が一致しませんでした。");
        }
        return new NextMoveEvidence.RevenuePart(
                count, totalRevenue, totalExpense, totalProfit, totalWorkMinutes, List.copyOf(recent));
    }

    // Integer の範囲を超えたら、切り捨てたり丸めたりせず、取得失敗にする（呼び出し元は AI を呼ばない）。
    private static Integer toInteger(Long value, String name) throws SQLException {
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException e) {
            throw new SQLException(name + "がIntegerの範囲を超えています。", e);
        }
    }

    private static <T> T required(T value, String name) throws SQLException {
        if (value == null) {
            throw new SQLException(name + "を読み取れませんでした。");
        }
        return value;
    }
}
