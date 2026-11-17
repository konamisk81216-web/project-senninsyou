package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

// 収益機会の「実売上・実利益・想定との差」で、記録0件（未記録）を0円として扱わないことを確かめる。
// ・関連タスクの収益記録が0件、またはタスク未登録 → 実績は null（画面とAI将軍への文では「未記録」）、想定との差も null。
// ・記録はあるが合計0円 → 0円（正しい実績）。想定との差も計算する。
// 本番DBにはつながない（FakeDatabase と、メモリ上の偽の読み取りを使う）。OpenAI も呼ばない。実行: mvn test
class OpportunityUnrecordedActualsTest {

    // 登録していない数値の列は、この値で返る。記録0件のときに合計の列を読んでいないことを確かめるため、目立つ値にする。
    private static final int UNREGISTERED_NUMBER = 999;

    @Test
    void 関連タスクがあっても記録0件なら_実績と想定との差はnull() {
        Opportunity opportunity = only(row(1, 10, 0, null, null, null));

        assertEquals(0, opportunity.revenueRecordCount());
        assertNull(opportunity.actualRevenue(), "0円ではなく未記録");
        assertNull(opportunity.actualExpense());
        assertNull(opportunity.actualProfit());
        assertNull(opportunity.revenueVariance(), "想定との差（−想定収益）を出さない");
    }

    @Test
    void タスク未登録なら_実績と想定との差はnull() {
        Opportunity opportunity = only(row(2, null, 0, null, null, null));

        assertNull(opportunity.linkedTaskId());
        assertNull(opportunity.actualRevenue());
        assertNull(opportunity.actualProfit());
        assertNull(opportunity.revenueVariance());
    }

    @Test
    void 記録はあるが合計0円なら_0円の実績として返す() {
        Opportunity opportunity = only(row(3, 11, 2, 0, 0, 0));

        assertEquals(2, opportunity.revenueRecordCount());
        assertEquals(0, opportunity.actualRevenue().compareTo(BigDecimal.ZERO), "記録ありの0円は0円");
        assertEquals(0, opportunity.actualProfit().compareTo(BigDecimal.ZERO));
        assertEquals(0, opportunity.revenueVariance().compareTo(BigDecimal.valueOf(-1000)), "0円 − 想定1000円");
    }

    @Test
    void 記録があれば_件数と合計をそのまま返す() {
        Opportunity opportunity = only(row(4, 12, 3, 1500, 200, 1300));

        assertEquals(3, opportunity.revenueRecordCount());
        assertEquals(0, opportunity.actualRevenue().compareTo(BigDecimal.valueOf(1500)));
        assertEquals(0, opportunity.actualExpense().compareTo(BigDecimal.valueOf(200)));
        assertEquals(0, opportunity.actualProfit().compareTo(BigDecimal.valueOf(1300)));
        assertEquals(0, opportunity.revenueVariance().compareTo(BigDecimal.valueOf(500)));
    }

    @Test
    void 一件ずつ読む処理でも_記録0件はnull() throws SQLException {
        Connection connection = FakeDatabase.connection(null, UNREGISTERED_NUMBER, 0,
                Map.of("FROM opportunities o", List.of(row(5, 13, 0, null, null, null))));

        Opportunity opportunity = new OpportunityRepository().getById(connection, 5);

        assertNull(opportunity.actualRevenue());
        assertNull(opportunity.revenueVariance());
    }

    @Test
    void 合計の列が読めなければ_0円にせず失敗として返す() {
        Map<String, Object> broken = row(6, 14, 1, null, 0, 0);
        broken.put("actual_revenue", new SQLException("テスト用にわざと読めなくした列"));
        Connection connection = FakeDatabase.connection(null, UNREGISTERED_NUMBER, 0,
                Map.of("FROM opportunities o", List.of(broken)));

        assertThrows(SQLException.class, () -> new OpportunityRepository().getAll(connection));
    }

    @Test
    void 一覧の読み取りに失敗したら_失敗として返す() {
        Connection connection = FakeDatabase.connection("FROM opportunities", UNREGISTERED_NUMBER, 0);

        assertThrows(SQLException.class, () -> new OpportunityRepository().getAll(connection));
    }

    // ---- AI将軍に渡す文（RevenueContextService）----

    @Test
    void AI将軍への文_記録0件なら累計も機会も未記録と書き_0円や想定との差を書かない() {
        String context = context(summary(0, 0), List.of(opportunity(10, 0, null, null)));

        assertTrue(context.contains("累計売上: 未記録"), context);
        assertTrue(context.contains("累計の案件利益（売上−案件の直接経費。月々の費用は差引前）: 未記録"), context);
        assertTrue(context.contains("総作業時間: 未記録"), context);
        assertTrue(context.contains("機会10 | タスク状態 進行中 | 想定収益 1000円 | 実績 未記録"), context);
        assertTrue(context.contains("想定との差 算出不可"), context);
        assertFalse(context.contains("累計売上: 0円"), context);
        assertFalse(context.contains("実売上 0円"), context);
        assertFalse(context.contains("-1000"), "記録0件で −想定収益 を書かない");
    }

    @Test
    void AI将軍への文_記録はあるが合計0円なら0円と書く() {
        String context = context(summary(0, 2), List.of(opportunity(11, 2, BigDecimal.ZERO, BigDecimal.ZERO)));

        assertTrue(context.contains("累計売上: 0円"), context);
        assertTrue(context.contains("総作業時間: 0分"), context);
        assertTrue(context.contains("機会11 | タスク状態 進行中 | 想定収益 1000円 | 収益記録 2件 | 実売上 0円 | 実利益 0円 | 想定との差 -1000円"),
                context);
        assertFalse(context.contains("未記録（収益記録0件"), context);
    }

    @Test
    void AI将軍への文_記録があれば件数と金額を書く() {
        String context = context(summary(1500, 3),
                List.of(opportunity(12, 3, BigDecimal.valueOf(1500), BigDecimal.valueOf(1300))));

        assertTrue(context.contains("累計売上: 1500円"), context);
        assertTrue(context.contains("収益記録 3件 | 実売上 1500円 | 実利益 1300円 | 想定との差 500円"), context);
    }

    @Test
    void AI将軍への文_記録ありなのに金額が欠けていれば_0円にせず算出不可と書く() {
        String context = context(summary(0, 1), List.of(opportunity(13, 1, null, null)));

        assertTrue(context.contains("収益記録 1件 | 実売上 算出不可 | 実利益 算出不可 | 想定との差 算出不可"), context);
        assertFalse(context.contains("実売上 0円"), context);
        assertFalse(context.contains("未記録（収益記録0件。0円の実績ではありません） | 想定との差"), "記録1件を0件と書かない");
    }

    // ---- 試験用の部品 ----

    private static Opportunity only(Map<String, Object> row) {
        Connection connection = FakeDatabase.connection(null, UNREGISTERED_NUMBER, 0,
                Map.of("FROM opportunities o", List.of(row)));
        try {
            List<Opportunity> opportunities = new OpportunityRepository().getAll(connection);
            assertEquals(1, opportunities.size());
            return opportunities.get(0);
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    // 収益機会の1行（想定収益1000円・60分・リスク中）。記録0件の行では合計の列を登録しない。
    private static Map<String, Object> row(
            long id, Integer taskId, int recordCount, Integer revenue, Integer expense, Integer profit) {
        Map<String, Object> row = FakeDatabase.row(
                "id", id, "title", "機会" + id, "opportunity_type", "note記事",
                "expected_revenue", 1000, "estimated_minutes", 60, "risk_level", "中",
                "status", "有望", "notes", "", "revenue_record_count", recordCount);
        if (taskId != null) {
            row.put("task_id", taskId);
            row.put("linked_task_name", "任務" + taskId);
            row.put("linked_task_status", "進行中");
        }
        if (revenue != null) {
            row.put("actual_revenue", revenue);
            row.put("actual_expense", expense);
            row.put("actual_profit", profit);
        }
        return row;
    }

    private static String context(RevenueSummary summary, List<Opportunity> opportunities) {
        RevenueRepository revenue = new RevenueRepository() {
            @Override
            public RevenueSummary getSummary() {
                return summary;
            }

            @Override
            public List<RevenueRecord> getRecent(int limit) {
                return List.of();
            }

            @Override
            public List<TaskRevenueAnalysis> getTaskAnalysis() {
                return List.of();
            }
        };
        OpportunityRepository opportunityRepository = new OpportunityRepository() {
            @Override
            public List<Opportunity> getAll() {
                return opportunities;
            }
        };
        return new RevenueContextService(revenue, opportunityRepository).buildContext();
    }

    private static RevenueSummary summary(int revenue, int records) {
        return new RevenueSummary(BigDecimal.valueOf(revenue), BigDecimal.ZERO, BigDecimal.valueOf(revenue),
                0, records, BigDecimal.ZERO, 0);
    }

    // 関連タスクのある収益機会（想定収益1000円）。記録0件なら実績は null。
    private static Opportunity opportunity(long id, int recordCount, BigDecimal revenue, BigDecimal profit) {
        BigDecimal expected = BigDecimal.valueOf(1000);
        return new Opportunity(id, "機会" + id, "note記事", expected, 60, "中", "有望", "",
                20, "任務20", "進行中", recordCount, revenue, recordCount == 0 ? null : BigDecimal.ZERO, profit,
                revenue == null ? null : revenue.subtract(expected), BigDecimal.valueOf(1000), 700, null);
    }
}
