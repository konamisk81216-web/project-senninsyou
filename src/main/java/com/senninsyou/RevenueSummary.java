package com.senninsyou;

import java.math.BigDecimal;

public class RevenueSummary {

    private final BigDecimal totalRevenue;
    private final BigDecimal totalExpense;
    private final BigDecimal totalProfit;
    private final int totalWorkMinutes;
    private final int recordCount;

    public RevenueSummary(
            BigDecimal totalRevenue,
            BigDecimal totalExpense,
            BigDecimal totalProfit,
            int totalWorkMinutes,
            int recordCount) {
        this.totalRevenue = totalRevenue;
        this.totalExpense = totalExpense;
        this.totalProfit = totalProfit;
        this.totalWorkMinutes = totalWorkMinutes;
        this.recordCount = recordCount;
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
}
