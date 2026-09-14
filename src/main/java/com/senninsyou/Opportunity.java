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
        BigDecimal expectedRevenuePerHour,
        int comparisonScore,
        OffsetDateTime createdAt) {
}
