package com.senninsyou;

import java.math.BigDecimal;

public record TaskRevenueAnalysis(
        Integer taskId,
        String taskName,
        BigDecimal totalRevenue,
        BigDecimal totalExpense,
        BigDecimal totalProfit,
        int totalWorkMinutes,
        int recordCount,
        BigDecimal profitMarginPercent,
        BigDecimal investmentRoiPercent,
        BigDecimal profitPerHour) {
}
