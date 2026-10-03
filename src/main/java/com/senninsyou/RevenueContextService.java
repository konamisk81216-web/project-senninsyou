package com.senninsyou;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

public class RevenueContextService {

    private static final int RECENT_RECORD_LIMIT = 5;

    private final RevenueRepository repository;
    private final OpportunityRepository opportunityRepository;

    public RevenueContextService() {
        this(new RevenueRepository(), new OpportunityRepository());
    }

    RevenueContextService(RevenueRepository repository) {
        this(repository, new OpportunityRepository());
    }

    RevenueContextService(
            RevenueRepository repository,
            OpportunityRepository opportunityRepository) {
        this.repository = repository;
        this.opportunityRepository = opportunityRepository;
    }

    public String buildContext() {
        try {
            RevenueSummary summary = repository.getSummary();
            List<RevenueRecord> recentRecords =
                    repository.getRecent(RECENT_RECORD_LIMIT);
            List<TaskRevenueAnalysis> taskAnalyses = repository.getTaskAnalysis();
            List<Opportunity> opportunities = opportunityRepository.getAll();

            StringBuilder context = new StringBuilder();
            context.append("累計売上: ")
                    .append(summary.getTotalRevenue().toPlainString())
                    .append("円\n");
            context.append("累計の案件の直接経費: ")
                    .append(summary.getTotalExpense().toPlainString())
                    .append("円\n");
            context.append("累計の案件利益（売上−案件の直接経費。月々の費用は差引前）: ")
                    .append(summary.getTotalProfit().toPlainString())
                    .append("円\n");
            context.append("総作業時間: ")
                    .append(formatWorkTime(summary.getTotalWorkMinutes()))
                    .append("\n");
            context.append("収益記録数: ")
                    .append(summary.getRecordCount())
                    .append("件\n");
            // 画面と同じ定義をAI将軍にも伝える。月々の費用を引いた「最終利益」はまだ算出していない。
            context.append("利益の定義: ここでの利益はすべて「案件利益＝売上−案件の直接経費」です。"
                    + "ChatGPTなど月々の費用は差し引いていません。最終利益や手取りと呼ばないでください。"
                    + "収益記録数が0件のときの0円は実績ではなく未記録です。\n");
            context.append("注意: 以下は利用者が入力した記録です。受注・販売・入金などの事実は別途確認が必要です。動作確認やテストの記録は事業実績として扱わないでください。\n");

            if (recentRecords.isEmpty()) {
                context.append("直近の収益記録: まだありません。\n");
            } else {
                context.append("直近の収益記録:\n");

                for (RevenueRecord record : recentRecords) {
                    context.append("- ")
                            .append(record.getOccurredOn())
                            .append(" | ")
                            .append(record.getDescription())
                            .append(" | 売上 ")
                            .append(record.getRevenue().toPlainString())
                            .append("円 | 直接経費 ")
                            .append(record.getExpense().toPlainString())
                            .append("円 | 案件利益 ")
                            .append(record.getProfit().toPlainString())
                            .append("円 | 作業 ")
                            .append(formatWorkTime(record.getWorkMinutes()))
                            .append("\n");
                }
            }

            appendTaskAnalysis(context, taskAnalyses);
            appendOpportunityAnalysis(context, opportunities);

            return context.toString();
        } catch (SQLException | IllegalStateException e) {
            System.out.println("AI将軍用の収益情報を取得できませんでした。");
            return "収益情報を取得できませんでした。タスク情報のみで判断してください。";
        }
    }

    private void appendTaskAnalysis(
            StringBuilder context,
            List<TaskRevenueAnalysis> analyses) {
        context.append("\nタスク別実績分析:\n");
        if (analyses.isEmpty()) {
            context.append("- 分析できるタスク別収益記録はありません。\n");
            return;
        }

        analyses.stream().limit(5).forEach(analysis -> context.append("- ")
                .append(analysis.taskName())
                .append(" | 案件利益 ")
                .append(analysis.totalProfit().toPlainString())
                .append("円 | 作業 ")
                .append(formatWorkTime(analysis.totalWorkMinutes()))
                .append(" | 1時間あたり案件利益 ")
                .append(formatMoneyOrUnavailable(analysis.profitPerHour()))
                .append(" | 投資ROI ")
                .append(formatPercentOrUnavailable(analysis.investmentRoiPercent()))
                .append("\n"));
    }

    private void appendOpportunityAnalysis(
            StringBuilder context,
            List<Opportunity> opportunities) {
        context.append("\n収益機会の想定対実績:\n");
        List<Opportunity> linkedOpportunities = opportunities.stream()
                .filter(opportunity -> opportunity.linkedTaskId() != null)
                .limit(5)
                .toList();

        if (linkedOpportunities.isEmpty()) {
            context.append("- タスク・実績まで関連付いた収益機会はありません。\n");
            return;
        }

        linkedOpportunities.forEach(opportunity -> context.append("- ")
                .append(opportunity.title())
                .append(" | タスク状態 ")
                .append(opportunity.linkedTaskStatus())
                .append(" | 想定収益 ")
                .append(opportunity.expectedRevenue().toPlainString())
                .append("円 | 実売上 ")
                .append(opportunity.actualRevenue().toPlainString())
                .append("円 | 実利益 ")
                .append(opportunity.actualProfit().toPlainString())
                .append("円 | 想定との差 ")
                .append(opportunity.revenueVariance().toPlainString())
                .append("円\n"));
    }

    private String formatMoneyOrUnavailable(BigDecimal value) {
        return value == null ? "算出不可" : value.toPlainString() + "円";
    }

    private String formatPercentOrUnavailable(BigDecimal value) {
        return value == null ? "算出不可" : value.toPlainString() + "%";
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
