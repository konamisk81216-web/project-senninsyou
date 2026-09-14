package com.senninsyou;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public class RevenueRecord {

    private final long id;
    private final Integer taskId;
    private final String description;
    private final BigDecimal revenue;
    private final BigDecimal expense;
    private final BigDecimal profit;
    private final int workMinutes;
    private final LocalDate occurredOn;
    private final String notes;
    private final OffsetDateTime createdAt;

    public RevenueRecord(
            long id,
            Integer taskId,
            String description,
            BigDecimal revenue,
            BigDecimal expense,
            BigDecimal profit,
            int workMinutes,
            LocalDate occurredOn,
            String notes,
            OffsetDateTime createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.description = description;
        this.revenue = revenue;
        this.expense = expense;
        this.profit = profit;
        this.workMinutes = workMinutes;
        this.occurredOn = occurredOn;
        this.notes = notes;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public Integer getTaskId() {
        return taskId;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getRevenue() {
        return revenue;
    }

    public BigDecimal getExpense() {
        return expense;
    }

    public BigDecimal getProfit() {
        return profit;
    }

    public int getWorkMinutes() {
        return workMinutes;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public String getNotes() {
        return notes;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
