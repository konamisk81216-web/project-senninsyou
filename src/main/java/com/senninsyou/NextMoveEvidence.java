package com.senninsyou;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// L-1「選んだ収益機会の根拠から、将軍が次の一手を提案する」の根拠。
// 1つの読み取りトランザクション（同じ時点のデータ）で読んだ、選んだ機会1件と、その機会に結び付いた任務1件と、
// その任務の収益記録だけを持つ。ほかの機会・任務・収益記録は入れない。
record NextMoveEvidence(
        OpportunityPart opportunity,
        TaskPart task,          // 任務未登録なら null
        RevenuePart revenue) {  // 任務未登録なら null（読まない）

    static final int RECENT_RECORD_LIMIT = 5;

    record OpportunityPart(
            long id,
            String title,
            String opportunityType,
            BigDecimal expectedRevenue,
            int estimatedMinutes,
            String riskLevel,
            String status,
            String notes,
            Integer taskId) {
    }

    record TaskPart(int id, String name, String status, String priority, String assignedAgent) {
    }

    // count は関連任務の収益記録の件数。0件なら合計は null（未記録。0円ではない）。
    // recent は直近の明細（最大 RECENT_RECORD_LIMIT 件）。件数と合計はすべての記録から数える。
    record RevenuePart(
            int count,
            BigDecimal totalRevenue,
            BigDecimal totalExpense,
            BigDecimal totalProfit,
            Integer totalWorkMinutes,
            List<RecordPart> recent) {

        boolean recorded() {
            return count > 0;
        }
    }

    record RecordPart(
            long id,
            LocalDate occurredOn,
            String description,
            BigDecimal revenue,
            BigDecimal expense,
            BigDecimal profit,
            int workMinutes) {
    }

    String opportunityEvidenceId() {
        return "opportunity:" + opportunity.id();
    }

    String taskEvidenceId() {
        return task == null ? null : "task:" + task.id();
    }

    // 収益記録の集計（未記録の場合も含む）の根拠番号。任務未登録なら null。
    String revenueSummaryEvidenceId() {
        return task == null ? null : "revenues:task" + task.id();
    }

    static String recordEvidenceId(RecordPart record) {
        return "revenue:" + record.id();
    }

    // AIに渡した根拠の番号。返答の根拠番号は、この中にあるものだけを認める。
    Set<String> evidenceIds() {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(opportunityEvidenceId());
        if (task != null) {
            ids.add(taskEvidenceId());
            ids.add(revenueSummaryEvidenceId());
            for (RecordPart record : revenue.recent()) {
                ids.add(recordEvidenceId(record));
            }
        }
        return ids;
    }
}
