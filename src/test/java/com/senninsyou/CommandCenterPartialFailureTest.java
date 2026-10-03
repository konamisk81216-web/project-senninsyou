package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

// 司令本部（5秒ごとの巡回）の読み取りが途中で1つだけ失敗したときに、その項目だけが null
// （画面では「取得できません」）になり、「0本」「タスクなし」「全員待機中」などの実績ゼロに化けないことを確かめる。
// 本番DBにはつながない（FakeDatabase を使う）。実行: mvn test
class CommandCenterPartialFailureTest {

    @Test
    void 失敗なし_本当に0件のときは0件として返す() {
        Map<String, Object> status = status(null);

        RevenueSummary money = assertInstanceOf(RevenueSummary.class, status.get("money"));
        assertFalse(money.isRevenueRecorded(), "売上記録0件は「未記録」用に false");
        assertEquals("0", numbers(status).get("articleCount"), "本当に0本のときは0本");
        assertEquals(List.of(), status.get("priorityTasks"), "本当にタスクがないときは空の一覧");
        assertEquals(List.of(), status.get("activities"), "本当に記録がないときは空の一覧");
        assertEquals("待機中", agentStatus(status, "発信AI"), "承認待ち0件なら発信AIは待機中");
    }

    @Test
    void 記事数の集計だけ失敗したら_numbersだけnull() {
        Map<String, Object> status = status("FROM writer_jobs");

        assertNull(status.get("numbers"), "「0本」にしない");
        assertNotNull(status.get("money"));
        assertNotNull(status.get("priorityTasks"));
        assertNotNull(status.get("agents"));
        assertNotNull(status.get("activities"));
    }

    @Test
    void 優先タスクの取得だけ失敗したら_priorityTasksだけnull() {
        Map<String, Object> status = status("FROM tasks");

        assertNull(status.get("priorityTasks"), "「未完了のタスクはありません」にしない");
        assertNotNull(status.get("numbers"));
    }

    @Test
    void お金の集計だけ失敗したら_moneyだけnull() {
        Map<String, Object> status = status("FROM revenue_records");

        assertNull(status.get("money"), "「0円」「未記録」にしない");
        assertNotNull(status.get("numbers"));
    }

    @Test
    void 承認待ちの件数だけ失敗したら_発信AIは取得できません() {
        Map<String, Object> status = status("status = '承認待ち'");

        assertEquals("取得できません", agentStatus(status, "発信AI"), "「待機中」にしない");
        assertEquals("待機中", agentStatus(status, "軍師AI"), "ほかの社員はそのまま");
    }

    @Test
    void AI社員の状態だけ失敗したら_agentsだけnull() {
        Map<String, Object> status = status("DISTINCT ON (agent)");

        assertNull(status.get("agents"), "「全員待機中」にしない");
        assertNotNull(status.get("activities"));
    }

    @Test
    void 実行ログだけ失敗したら_activitiesだけnull() {
        Map<String, Object> status = status("LIMIT 20");

        assertNull(status.get("activities"), "「まだ実行記録がありません」にしない");
        assertNotNull(status.get("agents"));
    }

    private static Map<String, Object> status(String failWhenSqlContains) {
        CommandCenterController controller = new CommandCenterController(
                new AiActivityRepository("unused", "unused", "unused"),
                new MetricsRepository("unused", "unused", "unused"),
                new RevenueRepository());
        return controller.status(FakeDatabase.connection(failWhenSqlContains));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> numbers(Map<String, Object> status) {
        Map<String, String> numbers = (Map<String, String>) status.get("numbers");
        assertNotNull(numbers);
        return numbers;
    }

    @SuppressWarnings("unchecked")
    private static String agentStatus(Map<String, Object> status, String agent) {
        List<Map<String, String>> agents = (List<Map<String, String>>) status.get("agents");
        assertTrue(agents != null, "agents が取れていること");
        return agents.stream()
                .filter(state -> agent.equals(state.get("agent")))
                .map(state -> state.get("status"))
                .findFirst()
                .orElse(null);
    }
}
