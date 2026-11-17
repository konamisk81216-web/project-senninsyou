package com.senninsyou;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Opportunity(
        long id,
        String title,
        String opportunityType,
        BigDecimal expectedRevenue,
        int estimatedMinutes,
        String riskLevel,
        String status,
        String notes,
        Integer linkedTaskId,
        String linkedTaskName,
        String linkedTaskStatus,
        // 関連タスクの収益記録の件数。0件（タスク未登録を含む）のとき、実売上・実経費・実利益・想定との差は
        // null（画面では「未記録」）。記録があって合計が0円のときは0円（正しい実績）。
        int revenueRecordCount,
        BigDecimal actualRevenue,
        BigDecimal actualExpense,
        BigDecimal actualProfit,
        BigDecimal revenueVariance,
        BigDecimal expectedRevenuePerHour,
        int comparisonScore,
        OffsetDateTime createdAt) {
}
