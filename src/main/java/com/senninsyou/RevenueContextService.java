package com.senninsyou;

import java.sql.SQLException;
import java.util.List;

public class RevenueContextService {

    private static final int RECENT_RECORD_LIMIT = 5;

    private final RevenueRepository repository;

    public RevenueContextService() {
        this(new RevenueRepository());
    }

    RevenueContextService(RevenueRepository repository) {
        this.repository = repository;
    }

    public String buildContext() {
        try {
            RevenueSummary summary = repository.getSummary();
            List<RevenueRecord> recentRecords =
                    repository.getRecent(RECENT_RECORD_LIMIT);

            StringBuilder context = new StringBuilder();
            context.append("累計売上: ")
                    .append(summary.getTotalRevenue().toPlainString())
                    .append("円\n");
            context.append("累計経費: ")
                    .append(summary.getTotalExpense().toPlainString())
                    .append("円\n");
            context.append("累計利益: ")
                    .append(summary.getTotalProfit().toPlainString())
                    .append("円\n");
            context.append("総作業時間: ")
                    .append(formatWorkTime(summary.getTotalWorkMinutes()))
                    .append("\n");
            context.append("収益記録数: ")
                    .append(summary.getRecordCount())
                    .append("件\n");

            if (recentRecords.isEmpty()) {
                context.append("直近の収益記録: まだありません。");
                return context.toString();
            }

            context.append("直近の収益記録:\n");

            for (RevenueRecord record : recentRecords) {
                context.append("- ")
                        .append(record.getOccurredOn())
                        .append(" | ")
                        .append(record.getDescription())
                        .append(" | 売上 ")
                        .append(record.getRevenue().toPlainString())
                        .append("円 | 経費 ")
                        .append(record.getExpense().toPlainString())
                        .append("円 | 利益 ")
                        .append(record.getProfit().toPlainString())
                        .append("円 | 作業 ")
                        .append(formatWorkTime(record.getWorkMinutes()))
                        .append("\n");
            }

            return context.toString();
        } catch (SQLException | IllegalStateException e) {
            System.out.println("AI将軍用の収益情報を取得できませんでした。");
            return "収益情報を取得できませんでした。任務情報のみで判断してください。";
        }
    }

    private String formatWorkTime(int totalMinutes) {
        int hours = totalMinutes / 60;
        int minutes = totalMinutes % 60;

        if (hours == 0) {
            return minutes + "分";
        }

        return hours + "時間" + minutes + "分";
    }
}
