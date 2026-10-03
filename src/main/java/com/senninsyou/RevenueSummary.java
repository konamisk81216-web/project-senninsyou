package com.senninsyou;

import java.math.BigDecimal;

// 売上・直接経費・案件利益・作業時間・月々の費用の集計。
// 画面の数字はすべてこの1つから作る（RevenueRepository.getSummary が唯一の集計元）。
public class RevenueSummary {

    private final BigDecimal totalRevenue;
    private final BigDecimal totalExpense;
    // 案件利益＝売上−案件の直接経費。月々の費用は差し引かない。
    private final BigDecimal totalProfit;
    private final int totalWorkMinutes;
    private final int recordCount;
    // 月々の費用は手入力した記録の合計で、OpenAIやAzureの実請求額ではない。
    private final BigDecimal monthlyCost;
    private final int monthlyCostCount;

    public RevenueSummary(
            BigDecimal totalRevenue,
            BigDecimal totalExpense,
            BigDecimal totalProfit,
            int totalWorkMinutes,
            int recordCount,
            BigDecimal monthlyCost,
            int monthlyCostCount) {
        this.totalRevenue = totalRevenue;
        this.totalExpense = totalExpense;
        this.totalProfit = totalProfit;
        this.totalWorkMinutes = totalWorkMinutes;
        this.recordCount = recordCount;
        this.monthlyCost = monthlyCost;
        this.monthlyCostCount = monthlyCostCount;
    }

    public BigDecimal getTotalRevenue() {
        return totalRevenue;
    }

    public BigDecimal getTotalExpense() {
        return totalExpense;
    }

    public BigDecimal getTotalProfit() {
        return totalProfit;
    }

    public int getTotalWorkMinutes() {
        return totalWorkMinutes;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public BigDecimal getMonthlyCost() {
        return monthlyCost;
    }

    public int getMonthlyCostCount() {
        return monthlyCostCount;
    }

    // 記録が1件もないときの0は実績0ではないので、画面では「未記録」と出す。
    public boolean isRevenueRecorded() {
        return recordCount > 0;
    }

    public boolean isMonthlyCostRecorded() {
        return monthlyCostCount > 0;
    }
}
