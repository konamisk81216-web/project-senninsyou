package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

// AI社員の記録を画面に出すときの状態の決め方を確かめる。時間がかかっているだけの記録を「エラー」にしない。
// 本番DBにはつながない（FakeDatabase を使う）。実行: mvn test
class AiActivityStatusTest {

    private static final double LIMIT_SECONDS = AiActivityRepository.STALE_MINUTES * 60;
    private final AiActivityRepository repository = new AiActivityRepository("unused", "unused", "unused");

    @Test
    void 作業中の記録は_10分までは作業中のまま() {
        assertEquals("作業中", logStatus("作業中", 5));
        assertEquals("作業中", logStatus("作業中", LIMIT_SECONDS), "ちょうど10分はまだ作業中");
    }

    @Test
    void 作業中のまま10分を超えた記録は_失敗ではなく長時間実行中() {
        assertEquals("長時間実行中", logStatus("作業中", LIMIT_SECONDS + 1));
        assertEquals("長時間実行中", logStatus("作業中", 3 * 60 * 60), "何時間たっても失敗とは決めない");
    }

    @Test
    void 長時間実行のあとに完了や失敗になれば_その結果を出す() {
        // 記録が更新されれば、始まってからの時間に関係なく、記録どおりの結果を出す。
        assertEquals("完了", logStatus("完了", 30 * 60));
        assertEquals("エラー", logStatus("失敗", 30 * 60));
    }

    @Test
    void 本当の失敗は_すぐにエラー() {
        assertEquals("エラー", logStatus("失敗", 5));
    }

    @Test
    void AI社員の一覧でも同じ決め方() {
        assertEquals("作業中", agentStatus("作業中", 5));
        assertEquals("長時間実行中", agentStatus("作業中", LIMIT_SECONDS + 1));
        assertEquals("エラー", agentStatus("失敗", 5));
        assertEquals("待機中", agentStatus("完了", 30 * 60), "完了した社員は待機中");
    }

    private String logStatus(String dbStatus, double ageSeconds) {
        List<Map<String, Object>> rows = List.of(FakeDatabase.row(
                "id", 1L, "agent", "ライターAI", "action", "記事を書く", "status", dbStatus, "detail", "",
                "age_seconds", ageSeconds, "duration_seconds", null));
        List<Map<String, String>> activities = repository.recentActivities(
                FakeDatabase.connection(null, 0, 0, Map.of("LIMIT 20", rows)));
        return activities.get(0).get("status");
    }

    private String agentStatus(String dbStatus, double ageSeconds) {
        List<Map<String, Object>> rows = List.of(FakeDatabase.row(
                "agent", "ライターAI", "action", "記事を書く", "status", dbStatus, "age_seconds", ageSeconds));
        List<Map<String, String>> agents = repository.agentStates(
                FakeDatabase.connection(null, 0, 0, Map.of("DISTINCT ON (agent)", rows)), 0);
        return agents.stream()
                .filter(agent -> "ライターAI".equals(agent.get("agent")))
                .findFirst()
                .orElseThrow()
                .get("status");
    }
}
