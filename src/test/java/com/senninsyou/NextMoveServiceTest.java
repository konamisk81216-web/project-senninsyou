package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

// L-1「選んだ収益機会の根拠から、将軍が次の一手を提案する」の流れの試験。
// 偽のDB（NextMoveFakeDatabase）・偽のAI・偽の実行記録だけを使う。本番DB・OpenAIにはつながない。実行: mvn test
class NextMoveServiceTest {

    // ---- 偽のAIと偽の実行記録 ----

    static final class FakeAi implements NextMoveAi {
        final List<String> events;
        final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        boolean available = true;
        Function<String, String> reply = prompt -> null;

        FakeAi(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String ask(String prompt) {
            events.add("ai:ask");
            prompts.add(prompt);
            return reply.apply(prompt);
        }
    }

    static final class FakeLog implements NextMoveService.ActivityLog {
        final List<String> events;
        long startId = 77;
        AiActivityRepository.FinishResult finishResult = AiActivityRepository.FinishResult.SAVED;
        final List<String> finishes = Collections.synchronizedList(new ArrayList<>());
        int starts;

        FakeLog(List<String> events) {
            this.events = events;
        }

        @Override
        public synchronized long start(String agent, String action) {
            starts++;
            events.add("log:start:" + agent);
            return startId;
        }

        @Override
        public AiActivityRepository.FinishResult finish(long id, String status, String detail) {
            events.add("log:finish:" + status);
            finishes.add(id + ":" + status + ":" + detail);
            return finishResult;
        }
    }

    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final NextMoveFakeDatabase db = new NextMoveFakeDatabase(events);
    final FakeAi ai = new FakeAi(events);
    final FakeLog log = new FakeLog(events);
    final NextMoveService service = new NextMoveService(db::connection, new NextMoveEvidenceReader(), log, ai);

    NextMoveServiceTest() {
        // 機会A（任務10・記録2件）、機会B（任務20・記録1件）、機会C（任務30・記録0件）、機会D（任務未登録）、機会E（任務40・合計0円）
        db.opportunities.add(new NextMoveFakeDatabase.OpportunityRow(1, "機会A", 1000, 10, "Aのメモ"));
        db.opportunities.add(new NextMoveFakeDatabase.OpportunityRow(2, "機会B", 5000, 20, "Bのメモ"));
        db.opportunities.add(new NextMoveFakeDatabase.OpportunityRow(3, "機会C", 1000, 30, ""));
        db.opportunities.add(new NextMoveFakeDatabase.OpportunityRow(4, "機会D", 1000, null, ""));
        db.opportunities.add(new NextMoveFakeDatabase.OpportunityRow(5, "機会E", 1000, 40, ""));
        db.tasks.add(new NextMoveFakeDatabase.TaskRow(10, "任務A", "進行中"));
        db.tasks.add(new NextMoveFakeDatabase.TaskRow(20, "任務B", "完了"));
        db.tasks.add(new NextMoveFakeDatabase.TaskRow(30, "任務C", "未着手"));
        db.tasks.add(new NextMoveFakeDatabase.TaskRow(40, "任務E", "進行中"));
        db.revenues.add(new NextMoveFakeDatabase.RevenueRow(101, 10, LocalDate.of(2026, 11, 1), "A記録1", 1200, 200, 30));
        db.revenues.add(new NextMoveFakeDatabase.RevenueRow(102, 10, LocalDate.of(2026, 11, 3), "A記録2", 300, 0, 15));
        db.revenues.add(new NextMoveFakeDatabase.RevenueRow(201, 20, LocalDate.of(2026, 11, 2), "B記録1", 9000, 1000, 90));
        db.revenues.add(new NextMoveFakeDatabase.RevenueRow(401, 40, LocalDate.of(2026, 11, 4), "E記録1", 0, 0, 20));
        db.revenues.add(new NextMoveFakeDatabase.RevenueRow(402, 40, LocalDate.of(2026, 11, 5), "E記録2", 0, 0, 10));
        ai.reply = prompt -> answer(firstId(prompt));
    }

    // 正しい返答（根拠番号はプロンプトの最初の番号＝その機会の番号）。
    static String answer(String evidenceId) {
        return "{\"nextMove\":\"読者の反応を確かめる小さな記事を出す\",\"reasons\":[{\"text\":\"想定より検証が足りない\",\"evidenceIds\":[\""
                + evidenceId + "\"]}],\"missingInfo\":\"販売ページの閲覧数\"}";
    }

    static String firstId(String prompt) {
        int start = prompt.indexOf("[opportunity:");
        return prompt.substring(start + 1, prompt.indexOf(']', start));
    }

    static Map<String, String> facts(NextMoveService.Result result) {
        return result.facts().stream().collect(Collectors.toMap(
                NextMovePrompt.Fact::label, NextMovePrompt.Fact::value, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    // ---- 同じ時点のデータ・AIの前に読み取りを終える ----

    @Test
    void 読み取りは1つの読み取り専用トランザクションで行い_確定して閉じてからAIを呼ぶ() {
        NextMoveService.Result result = service.propose(1);

        assertEquals(NextMoveService.Status.OK, result.status());
        assertEquals(List.of(
                "db:autoCommit=false", "db:readOnly=true", "db:isolation=REPEATABLE_READ",
                "db:query:opportunity[1]", "db:query:task[10]", "db:query:revenueTotal[10]", "db:query:revenueRecent[10, 5]",
                "db:commit", "db:close",
                "log:start:AI将軍", "ai:ask", "log:finish:完了"), events);
    }

    // ---- 別案件の混入 ----

    @Test
    void 機会Aの根拠とAIへの文に_機会Bの名前_金額_番号が入らない() {
        NextMoveService.Result result = service.propose(1);
        String prompt = ai.prompts.get(0);

        assertTrue(prompt.contains("[opportunity:1] 収益機会: 機会A"), prompt);
        assertTrue(prompt.contains("[task:10] 関連任務: 任務A"), prompt);
        assertTrue(prompt.contains("[revenues:task10] 関連任務の収益記録: 2件 / 売上合計 1,500円"), prompt);
        assertTrue(prompt.contains("[revenue:102] 収益記録: 2026-11-03 / A記録2"), prompt);
        assertTrue(prompt.contains("[revenue:101]"), prompt);
        for (String other : List.of("機会B", "任務B", "B記録1", "Bのメモ", "9,000", "5,000", "revenue:201", "task:20", "機会C", "機会D", "機会E")) {
            assertFalse(prompt.contains(other), "別の案件が混ざった: " + other);
            assertFalse(result.facts().toString().contains(other), "画面の根拠に別の案件が混ざった: " + other);
        }
        assertEquals(Map.of(
                "収益機会", "機会A（opportunity:1）", "想定収益", "1,000円", "関連任務", "任務A（進行中、task:10）",
                "収益記録", "2件", "実売上", "1,500円", "実利益", "1,300円", "想定との差", "+500円"), facts(result));
    }

    @Test
    void 別の機会の根拠番号を使った返答は_決まりに合わず提案を出さない() {
        ai.reply = prompt -> answer("revenue:201");

        NextMoveService.Result result = service.propose(1);

        assertEquals(NextMoveService.Status.INVALID_ANSWER, result.status());
        assertNull(result.proposal());
        assertNull(result.facts());
        assertEquals(List.of("77:失敗:渡していない根拠の番号が含まれていました"), log.finishes);
    }

    // ---- 未記録と0円 ----

    @Test
    void 記録0件なら_未記録と出し_0円や想定との差を出さない() {
        NextMoveService.Result result = service.propose(3);
        String prompt = ai.prompts.get(0);

        assertEquals(NextMoveService.Status.OK, result.status());
        assertTrue(prompt.contains("[revenues:task30] 関連任務の収益記録: 未記録（収益記録0件。0円の実績ではありません）"), prompt);
        assertFalse(prompt.contains("売上合計"), prompt);
        assertEquals(Map.of(
                "収益機会", "機会C（opportunity:3）", "想定収益", "1,000円", "関連任務", "任務C（未着手、task:30）",
                "収益記録", "0件", "実売上", "未記録", "実利益", "未記録", "想定との差", "算出不可"), facts(result));
    }

    @Test
    void 記録はあるが合計0円なら_0円の実績として出す() {
        NextMoveService.Result result = service.propose(5);

        assertEquals(NextMoveService.Status.OK, result.status());
        assertEquals("2件", facts(result).get("収益記録"));
        assertEquals("0円", facts(result).get("実売上"));
        assertEquals("0円", facts(result).get("実利益"));
        assertEquals("-1,000円", facts(result).get("想定との差"));
        assertTrue(ai.prompts.get(0).contains("2件 / 売上合計 0円"), ai.prompts.get(0));
    }

    @Test
    void 任務未登録なら_任務未登録と出し_任務と収益記録は読まない() {
        NextMoveService.Result result = service.propose(4);
        String prompt = ai.prompts.get(0);

        assertEquals(NextMoveService.Status.OK, result.status());
        assertTrue(prompt.contains("関連任務: 任務未登録"), prompt);
        assertFalse(prompt.contains("[task:"), prompt);
        assertEquals(List.of(NextMoveEvidenceReader.OPPORTUNITY_SQL), db.executedSql);
        assertEquals("任務未登録", facts(result).get("関連任務"));
        assertEquals("対象なし（任務未登録）", facts(result).get("収益記録"));
        assertFalse(facts(result).containsKey("実売上"));
    }

    // ---- 取得失敗：AIも実行記録も0回 ----

    @Test
    void 機会_任務_収益記録のどの読み取りに失敗しても_AIも実行記録も呼ばない() {
        for (String failing : List.of("FROM opportunities", "FROM tasks", "COUNT(*)", "ORDER BY occurred_on")) {
            events.clear();
            db.failWhenSqlContains = failing;

            NextMoveService.Result result = service.propose(1);

            assertEquals(NextMoveService.Status.READ_FAILED, result.status(), failing);
            assertNull(result.facts(), failing);
            assertNull(result.proposal(), failing);
            assertFalse(events.contains("ai:ask"), failing);
            assertFalse(events.stream().anyMatch(event -> event.startsWith("log:")), failing);
            assertTrue(events.contains("db:rollback"), "取り消す: " + failing);
            assertEquals("db:close", events.get(events.size() - 1), "接続を閉じる: " + failing);
        }
    }

    @Test
    void 機会は任務に結び付いているのに任務が読めなければ_任務未登録にせず取得失敗() {
        db.tasks.removeIf(task -> task.id() == 10);

        NextMoveService.Result result = service.propose(1);

        assertEquals(NextMoveService.Status.READ_FAILED, result.status());
        assertTrue(ai.prompts.isEmpty());
        assertEquals(0, log.starts);
    }

    @Test
    void 接続を開けなければ_取得失敗でAIを呼ばない() {
        NextMoveService broken = new NextMoveService(() -> {
            throw new java.sql.SQLException("テスト用に接続を失敗させた");
        }, new NextMoveEvidenceReader(), log, ai);

        assertEquals(NextMoveService.Status.READ_FAILED, broken.propose(1).status());
        assertTrue(ai.prompts.isEmpty());
        assertEquals(0, log.starts);
    }

    @Test
    void 機会が存在しなければ_AIも実行記録も呼ばない() {
        NextMoveService.Result result = service.propose(999);

        assertEquals(NextMoveService.Status.NOT_FOUND, result.status());
        assertTrue(ai.prompts.isEmpty());
        assertEquals(0, log.starts);
    }

    @Test
    void APIキーがなければ_実行記録を始めずAIも呼ばない() {
        ai.available = false;

        NextMoveService.Result result = service.propose(1);

        assertEquals(NextMoveService.Status.AI_UNAVAILABLE, result.status());
        assertTrue(ai.prompts.isEmpty());
        assertEquals(0, log.starts);
    }

    // ---- 実行記録 ----

    @Test
    void 開始の記録に失敗したら_AIを呼ばない() {
        log.startId = -1;

        NextMoveService.Result result = service.propose(1);

        assertEquals(NextMoveService.Status.RECORD_START_FAILED, result.status());
        assertEquals("実行記録を開始できなかったため、提案しませんでした。", result.message());
        assertTrue(ai.prompts.isEmpty());
        assertTrue(log.finishes.isEmpty());
    }

    @Test
    void 返答のあとに完了記録が更新0件なら_提案は出し_保存を確認できないと添える_呼び直さない() {
        log.finishResult = AiActivityRepository.FinishResult.NOT_SAVED;

        NextMoveService.Result result = service.propose(1);

        assertEquals(NextMoveService.Status.OK, result.status());
        assertNotNull(result.proposal());
        assertEquals("完了記録の保存を確認できませんでした。", result.recordNote());
        assertEquals(1, ai.prompts.size(), "AIを呼び直さない");
        assertEquals(1, log.finishes.size(), "記録を呼び直さない");
    }

    @Test
    void 返答のあとに完了記録の保存結果が不明なら_提案は出し_保存を確認できないと添える() {
        log.finishResult = AiActivityRepository.FinishResult.UNKNOWN;

        NextMoveService.Result result = service.propose(1);

        assertEquals(NextMoveService.Status.OK, result.status());
        assertEquals("完了記録の保存を確認できませんでした。", result.recordNote());
        assertEquals(1, ai.prompts.size());
        assertEquals(1, log.finishes.size());
    }

    @Test
    void 完了記録を保存できたときは_注意書きを出さない() {
        NextMoveService.Result result = service.propose(1);

        assertNull(result.recordNote());
        assertEquals(List.of("77:完了:"), log.finishes);
        assertEquals("提案だけです。AI社員は実行していません。", result.notice());
    }

    @Test
    void AIの返答が取れなければ_記録は失敗で閉じ_提案は出さず_呼び直さない() {
        ai.reply = prompt -> null;

        NextMoveService.Result result = service.propose(1);

        assertEquals(NextMoveService.Status.AI_FAILED, result.status());
        assertNull(result.proposal());
        assertEquals(1, ai.prompts.size());
        assertEquals(List.of("77:失敗:返答を受け取れませんでした"), log.finishes);
    }

    @Test
    void AIの呼び出しが例外を投げても_失敗として閉じ_同じ機会の作成中を解除する() {
        ai.reply = prompt -> {
            throw new IllegalStateException("テスト用の例外");
        };

        assertEquals(NextMoveService.Status.AI_FAILED, service.propose(1).status());

        ai.reply = prompt -> answer(firstId(prompt));
        assertEquals(NextMoveService.Status.OK, service.propose(1).status(), "作成中のままにならない");
    }

    // ---- 返答の検証 ----

    @Test
    void 金額や件数を書いた返答は_決まりに合わず提案を出さない() {
        for (String text : List.of("売上0円なので", "想定1,000円に届かない", "記録が2件ある", "￥500の利益", "ゼロ円のため", "達成率50%")) {
            log.finishes.clear();
            ai.reply = prompt -> "{\"nextMove\":\"記事を出す\",\"reasons\":[{\"text\":\"" + text
                    + "\",\"evidenceIds\":[\"opportunity:1\"]}],\"missingInfo\":\"\"}";

            NextMoveService.Result result = service.propose(1);

            assertEquals(NextMoveService.Status.INVALID_ANSWER, result.status(), text);
            assertNull(result.proposal(), text);
            assertEquals(List.of("77:失敗:提案の文に金額または件数が含まれていました"), log.finishes, text);
        }
    }

    @Test
    void 形の崩れた返答や決めていない項目を含む返答は_提案を出さない() {
        for (String raw : List.of(
                "JSONではない文",
                "{\"nextMove\":\"記事を出す\",\"reasons\":[],\"missingInfo\":\"\"}",
                "{\"nextMove\":\"\",\"reasons\":[{\"text\":\"理由\",\"evidenceIds\":[\"opportunity:1\"]}],\"missingInfo\":\"\"}",
                "{\"nextMove\":\"記事を出す\",\"reasons\":[{\"text\":\"理由\",\"evidenceIds\":[]}],\"missingInfo\":\"\"}",
                "{\"nextMove\":\"記事を出す\",\"reasons\":[{\"text\":\"理由\",\"evidenceIds\":[\"opportunity:1\"]}],\"missingInfo\":\"\",\"action\":\"startWriting\"}",
                "{\"nextMove\":\"記事を出す\",\"reasons\":[{\"text\":\"理由\",\"evidenceIds\":[\"opportunity:1\"],\"action\":\"runResearch\"}],\"missingInfo\":\"\"}")) {
            ai.reply = prompt -> raw;

            NextMoveService.Result result = service.propose(1);

            assertEquals(NextMoveService.Status.INVALID_ANSWER, result.status(), raw);
            assertNull(result.proposal(), raw);
        }
    }

    @Test
    void 返答の形は3項目だけで_実行の操作の項目がない() throws Exception {
        String schema = new ObjectMapper().writeValueAsString(NextMovePrompt.answerFormat());

        assertTrue(schema.contains("\"strict\":true"), schema);
        assertTrue(schema.contains("\"required\":[\"nextMove\",\"reasons\",\"missingInfo\"]"), schema);
        assertTrue(schema.contains("\"required\":[\"text\",\"evidenceIds\"]"), schema);
        assertFalse(schema.contains("action"), schema);
        assertFalse(schema.contains("assignedAgent"), schema);
        assertEquals(List.of("nextMove", "reasons", "missingInfo"), NextMovePrompt.ANSWER_FIELDS);
    }

    @Test
    void 提案は実行記録のAI将軍1行だけで_ほかのAI社員の記録を作らない() {
        service.propose(1);

        assertEquals(1, log.starts);
        assertEquals(List.of("log:start:AI将軍", "log:finish:完了"),
                events.stream().filter(event -> event.startsWith("log:")).toList());
    }

    @Test
    void 画面へ返す結果をJSONにできる() throws Exception {
        String json = new ObjectMapper().writeValueAsString(service.propose(1));

        assertTrue(json.contains("\"status\":\"OK\""), json);
        assertTrue(json.contains("\"facts\":[{\"label\":\"収益機会\""), json);
        assertTrue(json.contains("\"nextMove\":\"読者の反応を確かめる小さな記事を出す\""), json);
        assertTrue(json.contains("\"evidenceIds\":[\"opportunity:1\"]"), json);
        assertTrue(json.contains("\"notice\":\"提案だけです。AI社員は実行していません。\""), json);
    }

    // ---- 同時の呼び出し（同じアプリのプロセスの中） ----

    @Test
    void 同じ機会の提案を作成中なら_2つ目は断りAIは1回だけ_別の機会は進む_終われば解除() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ai.reply = prompt -> {
            if (prompt.contains("[opportunity:1]")) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return answer(firstId(prompt));
        };

        CompletableFuture<NextMoveService.Result> first = CompletableFuture.supplyAsync(() -> service.propose(1));
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        NextMoveService.Result second = service.propose(1);
        NextMoveService.Result other = service.propose(2);
        release.countDown();
        NextMoveService.Result firstResult = first.get(10, TimeUnit.SECONDS);

        assertEquals(NextMoveService.Status.BUSY, second.status());
        assertNull(second.proposal());
        assertEquals(NextMoveService.Status.OK, other.status(), "別の機会は断らない");
        assertEquals(NextMoveService.Status.OK, firstResult.status());
        assertEquals(1, ai.prompts.stream().filter(prompt -> prompt.contains("[opportunity:1]")).count(), "機会AのAI呼び出しは1回");
        assertEquals(NextMoveService.Status.OK, service.propose(1).status(), "終われば解除");
    }

    @Test
    void 失敗のあとも_同じ機会の作成中は解除される() {
        db.failWhenSqlContains = "FROM tasks";
        assertEquals(NextMoveService.Status.READ_FAILED, service.propose(1).status());
        db.failWhenSqlContains = null;

        log.startId = -1;
        assertEquals(NextMoveService.Status.RECORD_START_FAILED, service.propose(1).status());
        log.startId = 77;

        ai.reply = prompt -> null;
        assertEquals(NextMoveService.Status.AI_FAILED, service.propose(1).status());

        ai.reply = prompt -> "形の崩れた返答";
        assertEquals(NextMoveService.Status.INVALID_ANSWER, service.propose(1).status());

        ai.reply = prompt -> answer(firstId(prompt));
        assertEquals(NextMoveService.Status.OK, service.propose(1).status());
    }
}
