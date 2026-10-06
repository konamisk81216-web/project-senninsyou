package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

// 画面は revenueRecorded / monthlyCostRecorded を見て「未記録」か金額かを決める
// （売上・案件利益・作業時間・直接経費は revenueRecorded、月々の費用は monthlyCostRecorded）。
// 記録が0件のときと、0円の記録があるときを取り違えないことを確かめる。
class RevenueSummaryTest {

    @Test
    void 記録が0件なら_記録ありにならない() {
        RevenueSummary summary = summary(0, 0, 0, 0);

        assertFalse(summary.isRevenueRecorded(), "売上・直接経費などは「未記録」");
        assertFalse(summary.isMonthlyCostRecorded(), "月々の費用は「未記録」");
    }

    @Test
    void 金額0円の記録が1件あれば_記録ありで金額は0() {
        RevenueSummary summary = summary(0, 1, 0, 1);

        assertTrue(summary.isRevenueRecorded(), "0円の記録は「未記録」ではなく0円");
        assertEquals(0, summary.getTotalExpense().compareTo(BigDecimal.ZERO), "直接経費は0円");
        assertTrue(summary.isMonthlyCostRecorded(), "0円の月々の費用も記録あり");
    }

    @Test
    void 金額の記録があれば_記録ありでその金額() {
        RevenueSummary summary = summary(500, 2, 1200, 1);

        assertTrue(summary.isRevenueRecorded());
        assertEquals(0, summary.getTotalExpense().compareTo(BigDecimal.valueOf(500)));
    }

    private static RevenueSummary summary(int expense, int records, int monthlyCost, int costRecords) {
        return new RevenueSummary(
                BigDecimal.ZERO, BigDecimal.valueOf(expense), BigDecimal.ZERO.subtract(BigDecimal.valueOf(expense)),
                0, records, BigDecimal.valueOf(monthlyCost), costRecords);
    }
}
