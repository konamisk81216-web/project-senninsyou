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
        BigDecimal actualRevenue,
        BigDecimal actualExpense,
        BigDecimal actualProfit,
        BigDecimal revenueVariance,
        BigDecimal expectedRevenuePerHour,
        int comparisonScore,
        OffsetDateTime createdAt) {
}
