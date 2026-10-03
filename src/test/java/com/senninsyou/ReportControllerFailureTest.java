package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

// 「前回からの変化」パネルの読み取りが途中で1つだけ失敗したときに、その項目だけが null
// （画面では「取得できません」）になり、「0件」「直近のエラー：なし」「未登録」に化けないことを確かめる。
// あわせて、本当に0件・なし・未登録のときは、そのとおり返すことも確かめる。
// 本番DBにはつながない（FakeDatabase を使う）。実行: mvn test
class ReportControllerFailureTest {

    @Test
    void 失敗なし_本当に0件のときは0件_なし_未登録として返す() {
        Map<String, Object> report = report(null);

        assertEquals(true, report.get("available"));
        assertEquals(0, report.get("unfinishedCount"), "未完了タスク0件");
        assertEquals(List.of(), report.get("priorityTasks"));
        assertEquals(0, report.get("waitingApprovals"), "確認待ち0件");
        assertEquals(List.of(), report.get("recentErrors"), "直近のエラーなし");
        assertEquals("未登録", lastCompleted(report).get("action"), "完了記録がない");
        assertEquals("未登録", report.get("nextAction"));
        assertEquals("未登録", report.get("lastReportedAt"), "一度も確認していない");
        assertNotNull(report.get("agents"));
    }

    @Test
    void 未完了タスクの件数は表示用の上位5件ではなくDB上の件数() {
        // DBの未完了タスクは7件（COUNTは7）。表示用の一覧は本物のDBと同じく LIMIT 5 で5行だけ返る。
        ReportController controller = new ReportController(
                new AiActivityRepository("unused", "unused", "unused"));
        Map<String, Object> report = controller.report(FakeDatabase.connection(null, 7, 5));

        assertEquals(5, ((List<?>) report.get("priorityTasks")).size(), "表示用の一覧は5件");
        assertEquals(7, report.get("unfinishedCount"), "件数は一覧の5件ではなくDB上の7件");
    }

    @Test
    void 未完了タスクの件数だけ失敗したら_unfinishedCountだけnull() {
        Map<String, Object> report = report("COUNT(*) AS n FROM tasks");

        assertNull(report.get("unfinishedCount"), "「0件」にしない");
        assertEquals(List.of(), report.get("priorityTasks"), "一覧は取れている");
    }

    @Test
    void 優先タスクの一覧だけ失敗したら_priorityTasksがnullで次の作業も取得できません() {
        Map<String, Object> report = report("LIMIT 5");

        assertNull(report.get("priorityTasks"));
        assertEquals("取得できません", report.get("nextAction"), "「未登録」にしない");
        assertEquals(0, report.get("unfinishedCount"), "件数は取れている");
    }

    @Test
    void 確認待ちの件数だけ失敗したら_waitingApprovalsがnullで発信AIも取得できません() {
        Map<String, Object> report = report("FROM post_drafts");

        assertNull(report.get("waitingApprovals"), "「確認待ち：0件」にしない");
        assertEquals("取得できません", agentStatus(report, "発信AI"));
    }

    @Test
    void 直近のエラーだけ失敗したら_recentErrorsだけnull() {
        Map<String, Object> report = report("status = '失敗'");

        assertNull(report.get("recentErrors"), "「直近のエラー：なし」にしない");
        assertEquals(0, report.get("unfinishedCount"));
    }

    @Test
    void 最後に完了した作業だけ失敗したら_lastCompletedだけnull() {
        Map<String, Object> report = report("WHERE status = '完了'");

        assertNull(report.get("lastCompleted"), "「未登録」にしない");
        assertEquals(List.of(), report.get("recentErrors"));
    }

    @Test
    void 前回の確認日時だけ失敗したら_取得できません() {
        Map<String, Object> report = report("SELECT last_reported_at");

        assertEquals("取得できません", report.get("lastReportedAt"), "「未登録」にしない");
    }

    @Test
    void 失敗した項目は変化の目印で区別される() {
        String normal = (String) report(null).get("signature");
        String failed = (String) report("status = '失敗'").get("signature");

        assertTrue(failed.contains("?"), "取れなかった項目は ? になる");
        assertTrue(!normal.equals(failed), "本当に0件のときと目印が違う");
    }

    // ===== 「前回からの変化」の目印（回帰テスト） =====
    // 目印が変わる＝パネルに変化として出る。変わるべきときに変わり、同じときは変わらないことを確かめる。

    private static final List<Map<String, Object>> TOP5 = List.of(
            task("A", "高"), task("B", "高"), task("C", "中"), task("D", "中"), task("E", "低"));

    private static final List<Map<String, Object>> ERRORS = List.of(
            error("品質管理AI", "2026-10-01 10:00:00"),
            error("ライターAI", "2026-10-01 09:00:00"),
            error("営業AI", "2026-10-01 08:00:00"));

    @Test
    void 目印_同じ内容なら変わらない() {
        assertEquals(signature(7, TOP5, ERRORS), signature(7, TOP5, ERRORS));
    }

    @Test
    void 目印_未完了が7件から6件になれば_上位5件が同じでも変わる() {
        assertNotEquals(signature(7, TOP5, ERRORS), signature(6, TOP5, ERRORS));
    }

    @Test
    void 目印_優先タスクが入れ替われば_件数が同じでも変わる() {
        List<Map<String, Object>> replaced = List.of(
                task("A", "高"), task("B", "高"), task("C", "中"), task("D", "中"), task("F", "低"));
        assertNotEquals(signature(7, TOP5, ERRORS), signature(7, replaced, ERRORS));
    }

    @Test
    void 目印_優先タスクの順番が変われば変わる() {
        List<Map<String, Object>> reordered = List.of(
                task("B", "高"), task("A", "高"), task("C", "中"), task("D", "中"), task("E", "低"));
        assertNotEquals(signature(7, TOP5, ERRORS), signature(7, reordered, ERRORS));
    }

    @Test
    void 目印_新しいエラーが増えれば_表示件数が3件のままでも変わる() {
        List<Map<String, Object>> newer = List.of(
                error("発信AI", "2026-10-01 11:00:00"),
                error("品質管理AI", "2026-10-01 10:00:00"),
                error("ライターAI", "2026-10-01 09:00:00"));
        assertNotEquals(signature(7, TOP5, ERRORS), signature(7, TOP5, newer));
    }

    private static String signature(
            int unfinished, List<Map<String, Object>> tasks, List<Map<String, Object>> errors) {
        ReportController controller = new ReportController(
                new AiActivityRepository("unused", "unused", "unused"));
        // 未完了の件数だけを変えられるようにし、確認待ちなどほかの件数は2件で固定する
        // （ほかの件数の変化で、たまたま目印が変わってしまわないように）。
        Map<String, Object> report = controller.report(FakeDatabase.connection(null, 2, 0, Map.of(
                "COUNT(*) AS n FROM tasks", List.of(FakeDatabase.row("n", unfinished)),
                "LIMIT 5", tasks,
                "status = '失敗'", errors)));
        assertEquals(unfinished, report.get("unfinishedCount"));
        assertEquals(2, report.get("waitingApprovals"));
        return (String) report.get("signature");
    }

    private static Map<String, Object> task(String name, String priority) {
        return FakeDatabase.row("task_name", name, "priority", priority,
                "assigned_agent", "本人", "status", "未着手");
    }

    private static Map<String, Object> error(String agent, String startedAt) {
        return FakeDatabase.row("agent", agent, "action", "作業", "detail", "",
                "started_at", Timestamp.valueOf(startedAt));
    }

    private static Map<String, Object> report(String failWhenSqlContains) {
        ReportController controller = new ReportController(
                new AiActivityRepository("unused", "unused", "unused"));
        return controller.report(FakeDatabase.connection(failWhenSqlContains));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> lastCompleted(Map<String, Object> report) {
        Map<String, String> last = (Map<String, String>) report.get("lastCompleted");
        assertNotNull(last);
        return last;
    }

    @SuppressWarnings("unchecked")
    private static String agentStatus(Map<String, Object> report, String agent) {
        List<Map<String, String>> agents = (List<Map<String, String>>) report.get("agents");
        assertNotNull(agents);
        return agents.stream()
                .filter(state -> agent.equals(state.get("agent")))
                .map(state -> state.get("status"))
                .findFirst()
                .orElse(null);
    }
}
