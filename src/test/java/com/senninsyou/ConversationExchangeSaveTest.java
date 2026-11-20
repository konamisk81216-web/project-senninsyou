package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;

import com.senninsyou.ConversationRepository.ExchangeSave;

// 相談の1往復（利用者の発言とAI将軍の返答）を、同じ接続・同じトランザクションで保存することを、偽のDBで確かめる。
// 本物の OpenAI と DB にはつながない。AI は 127.0.0.1 の偽のサーバー、DB は接続の操作を順番どおり記録する偽物。実行: mvn test
class ConversationExchangeSaveTest {

    // ---- 偽のDB：接続を開く・設定・SQLの実行・確定・取り消し・閉じるを events に記録する ----

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private int failOnInsert;          // この番目の INSERT（1始まり）で失敗する。0なら失敗しない
    private boolean failOnCommit;      // commit で例外（確定の結果が分からない状態）
    private boolean failOnOpen;        // 接続できない
    private boolean runtimeOnInsert;   // INSERT で SQLException 以外の例外
    private int zeroRowsOnInsert;      // この番目の INSERT（1始まり）は、例外なしで更新件数0を返す。0ならしない

    private Connection open() throws SQLException {
        events.add("db:open");
        if (failOnOpen) {
            throw new SQLException("テスト用にわざと接続に失敗");
        }
        int[] inserts = {0};
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit" -> record("db:autoCommit=" + args[0]);
                    case "commit" -> {
                        events.add("db:commit");
                        if (failOnCommit) {
                            throw new SQLException("テスト用にわざと確定の応答を失わせた");
                        }
                        yield null;
                    }
                    case "rollback" -> record("db:rollback");
                    case "close" -> record("db:close");
                    case "isClosed" -> false;
                    case "prepareStatement" -> statement((String) args[0], inserts);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PreparedStatement statement(String sql, int[] inserts) {
        assertEquals(ConversationRepository.INSERT_SQL, sql);
        Map<Integer, Object> params = new HashMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString" -> {
                        params.put((Integer) args[0], args[1]);
                        yield null;
                    }
                    case "executeUpdate" -> {
                        inserts[0]++;
                        events.add("db:insert " + params.get(1) + "：" + params.get(2));
                        if (inserts[0] == failOnInsert) {
                            if (runtimeOnInsert) throw new IllegalStateException("テスト用の想定外の例外");
                            throw new SQLException("テスト用にわざと失敗させた INSERT（" + inserts[0] + "件目）");
                        }
                        yield inserts[0] == zeroRowsOnInsert ? 0 : 1;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Object record(String event) {
        events.add(event);
        return null;
    }

    private ConversationRepository repository() {
        return new ConversationRepository(this::open);
    }

    // ===== 保存の部品だけ =====

    @Test
    void 正常時は同じ接続で2件を書いてから1回だけ確定する() {
        assertEquals(ExchangeSave.SAVED, repository().addExchange("相談です", "ハル：価格を決めましょう。"));
        assertEquals(List.of("db:open", "db:autoCommit=false",
                "db:insert 利用者：相談です", "db:insert AI将軍：ハル：価格を決めましょう。",
                "db:commit", "db:close"), events);
    }

    @Test
    void 二件目のINSERTに失敗したら確定せず取り消して両方残さない() {
        failOnInsert = 2;
        assertEquals(ExchangeSave.NOT_SAVED, repository().addExchange("相談です", "ハル：価格です。"));
        assertEquals(List.of("db:open", "db:autoCommit=false",
                "db:insert 利用者：相談です", "db:insert AI将軍：ハル：価格です。",
                "db:rollback", "db:close"), events);
    }

    @Test
    void 一件目のINSERTに失敗したら二件目は書かずに取り消す() {
        failOnInsert = 1;
        assertEquals(ExchangeSave.NOT_SAVED, repository().addExchange("相談です", "ハル：価格です。"));
        assertEquals(List.of("db:open", "db:autoCommit=false", "db:insert 利用者：相談です", "db:rollback", "db:close"), events);
    }

    @Test
    void 想定外の例外でも取り消して例外を外に出さない() {
        failOnInsert = 2;
        runtimeOnInsert = true;
        assertEquals(ExchangeSave.NOT_SAVED, repository().addExchange("相談です", "ハル：価格です。"));
        assertFalse(events.contains("db:commit"));
        assertTrue(events.contains("db:rollback"));
        assertEquals("db:close", events.get(events.size() - 1));
    }

    @Test
    void 確定の結果が分からないときは保存されなかったと断定しない() {
        failOnCommit = true;
        assertEquals(ExchangeSave.UNKNOWN, repository().addExchange("相談です", "ハル：価格です。"));
        // 確定を送ったあとは取り消しを送らない（送っても結果は変わらず、保存されなかった証拠にもならない）。保存し直さない。
        assertEquals(List.of("db:open", "db:autoCommit=false",
                "db:insert 利用者：相談です", "db:insert AI将軍：ハル：価格です。",
                "db:commit", "db:close"), events);
    }

    @Test
    void 接続できなければ何も書かず例外も出さない() {
        failOnOpen = true;
        assertEquals(ExchangeSave.NOT_SAVED, repository().addExchange("相談です", "ハル：価格です。"));
        assertEquals(List.of("db:open"), events);
    }

    @Test
    void 文字数の上限は今までどおり4000文字で切る() {
        String longText = "あ".repeat(4001);
        repository().addExchange(longText, longText);
        String expected = "あ".repeat(4000);
        assertEquals(List.of("db:open", "db:autoCommit=false",
                "db:insert 利用者：" + expected, "db:insert AI将軍：" + expected,
                "db:commit", "db:close"), events);
    }

    @Test
    void 両方の本文が空なら接続を開かず保存しない() {
        assertEquals(ExchangeSave.NOT_SAVED, repository().addExchange(" ", ""));
        assertEquals(List.of(), events, "接続も開かない");
    }

    @Test
    void 利用者の発言だけが空なら接続を開かず返答も保存しない() {
        assertEquals(ExchangeSave.NOT_SAVED, repository().addExchange(" ", "ハル：価格です。"));
        assertEquals(List.of(), events, "接続も開かない（AI将軍の行だけを残さない）");
    }

    @Test
    void 返答だけが空なら接続を開かず発言も保存しない() {
        assertEquals(ExchangeSave.NOT_SAVED, repository().addExchange("相談です", ""));
        assertEquals(List.of(), events, "接続も開かない（利用者の行だけを残さない）");
    }

    @Test
    void 二件目のINSERTが0行なら確定せず取り消す() {
        zeroRowsOnInsert = 2;
        assertEquals(ExchangeSave.NOT_SAVED, repository().addExchange("相談です", "ハル：価格です。"));
        assertEquals(List.of("db:open", "db:autoCommit=false",
                "db:insert 利用者：相談です", "db:insert AI将軍：ハル：価格です。",
                "db:rollback", "db:close"), events);
    }

    @Test
    void 一件目のINSERTが0行なら二件目は書かずに取り消す() {
        zeroRowsOnInsert = 1;
        assertEquals(ExchangeSave.NOT_SAVED, repository().addExchange("相談です", "ハル：価格です。"));
        assertEquals(List.of("db:open", "db:autoCommit=false", "db:insert 利用者：相談です", "db:rollback", "db:close"), events);
    }

    @Test
    void 一件だけの保存は今までどおり自動確定で1件書く() {
        repository().add("AI将軍", "タスク案です。");
        assertEquals(List.of("db:open", "db:insert AI将軍：タスク案です。", "db:close"), events,
                "トランザクションは使わない（autoCommit を変えない・確定も取り消しも送らない）");
    }

    // ===== AIController を通した流れ（AIは偽のサーバー、DBは上の偽物） =====

    private HttpServer server;
    private volatile String body;
    private final List<String> activities = new ArrayList<>();
    private AIController controller;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            events.add("ai:request");
            byte[] bytes = ("{\"id\":\"resp_test\",\"object\":\"response\",\"created_at\":0,\"status\":\"completed\","
                    + "\"model\":\"gpt-5.2\",\"output\":[{\"type\":\"message\",\"id\":\"msg_test\",\"status\":\"completed\","
                    + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":" + quote(body) + ",\"annotations\":[]}]}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        controller = controllerWithFakes("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void 相談はAIの返答を検証し終えてから保存のトランザクションを始める() {
        body = answer("両方", "小さく売ります。", "決済は手作業で始めます。", "");
        String result = controller.command(request("discuss"));
        assertTrue(result.contains("\"speaker\":\"両方\""));
        assertEquals(List.of("ai:request", "db:open", "db:autoCommit=false",
                "db:insert 利用者：相談です", "db:insert AI将軍：ハル：小さく売ります。\nガク：決済は手作業で始めます。",
                "db:commit", "db:close"), events);
        assertEquals(List.of("start", "finish 完了 "), activities);
    }

    @Test
    void 二件目の保存に失敗しても生成済みの返答を返し_AIも保存も呼び直さない() {
        failOnInsert = 2;
        body = answer("ハル", "価格を決めましょう。", "", "");
        String result = controller.command(request("discuss"));
        assertTrue(result.contains("\"haruText\":\"価格を決めましょう。\""), "返答はそのまま返る");
        assertEquals(1, count("ai:request"), "AIの呼び直しはしない");
        assertEquals(1, count("db:open"), "保存のやり直しはしない");
        assertTrue(events.contains("db:rollback"));
        assertFalse(events.contains("db:commit"));
        assertEquals(List.of("start", "finish 完了 "), activities, "保存の失敗を返答形式のエラーにしない（実行ログは今までどおり）");
    }

    @Test
    void 確定の結果が分からなくても返答を返し_保存し直さない() {
        failOnCommit = true;
        body = answer("確認", "", "", "売上と作業のどちらを優先しますか？");
        String result = controller.command(request("discuss"));
        assertTrue(result.contains("\"speaker\":\"確認\""));
        assertEquals(1, count("ai:request"));
        assertEquals(1, count("db:open"));
        assertEquals(1, count("db:commit"));
        assertEquals(List.of("start", "finish 完了 "), activities);
    }

    @Test
    void 接続できなくても返答を返す() {
        failOnOpen = true;
        body = answer("ガク", "", "まず計測を入れます。", "");
        assertTrue(controller.command(request("discuss")).contains("\"speaker\":\"ガク\""));
        assertEquals(List.of("ai:request", "db:open"), events);
        assertEquals(List.of("start", "finish 完了 "), activities);
    }

    @Test
    void 保存の部品が想定外の例外を投げても返答形式のエラーにしない() throws Exception {
        set(controller, "conversationRepository", new ConversationRepository(this::open) {
            @Override public ExchangeSave addExchange(String userMessage, String aiMessage) {
                throw new IllegalStateException("テスト用の想定外の例外");
            }
        });
        body = answer("ハル", "価格を決めましょう。", "", "");
        assertTrue(controller.command(request("discuss")).contains("\"speaker\":\"ハル\""));
        assertEquals(List.of("start", "finish 完了 "), activities);
    }

    @Test
    void 返答の形式が正しくなければ保存のトランザクションは始めない() {
        body = answer("ハル", "価格です。", "技術です。", "");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> controller.command(request("discuss")));
        assertTrue(error.getMessage().contains("返答形式が正しくありません"));
        assertEquals(List.of("ai:request"), events, "接続を開かない");
        assertEquals(List.of("start", "finish 失敗 話者と発言の組み合わせが正しくありませんでした"), activities);
    }

    @Test
    void タスク案の整理は今までどおりAI将軍の要約1件だけを保存する() {
        String result = controllerPropose();
        assertTrue(result.contains("\"nextTask\":\"採点を終える\""));
        assertEquals(List.of("ai:request", "db:open", "db:insert AI将軍：タスク案です。", "db:close"), events);
        assertEquals(List.of("start", "finish 完了 "), activities);
    }

    private String controllerPropose() {
        body = "{\"summary\":\"タスク案です。\",\"revenueInsight\":\"\",\"performanceDecision\":\"\",\"marketOpportunity\":\"\","
                + "\"nextTask\":\"採点を終える\",\"priority\":\"中\",\"assignedAgent\":\"品質管理AI\",\"action\":\"\","
                + "\"actionLabel\":\"\",\"actionTheme\":\"\",\"actionAudience\":\"\"}";
        return controller.command(request("propose"));
    }

    private long count(String event) {
        synchronized (events) {
            return events.stream().filter(event::equals).count();
        }
    }

    private static String answer(String speaker, String haru, String gaku, String clarify) {
        return "{\"summary\":\"結論です。\",\"revenueInsight\":\"\",\"performanceDecision\":\"\",\"marketOpportunity\":\"\","
                + "\"nextTask\":\"\",\"priority\":\"\",\"assignedAgent\":\"\",\"action\":\"\",\"actionLabel\":\"\","
                + "\"actionTheme\":\"\",\"actionAudience\":\"\","
                + "\"speaker\":\"" + speaker + "\",\"speakerReason\":\"目的から判断\",\"haruText\":\"" + haru + "\","
                + "\"gakuText\":\"" + gaku + "\",\"clarifyQuestion\":\"" + clarify + "\"}";
    }

    private static AIController.CommandRequest request(String mode) {
        return new AIController.CommandRequest("相談です", mode.equals("propose") ? "利用者: これまでの相談" : "", mode);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // 本物の AIController を、DB につながない偽の部品で組み立てる（コンストラクタは DATABASE_URL を読むので通さない）。
    // 会話の保存だけは、本物の ConversationRepository に偽の接続を渡して使う。
    private AIController controllerWithFakes(String baseUrl) throws Exception {
        AIService ai = new AIService();
        set(ai, "client", OpenAIOkHttpClient.builder().apiKey("test").baseUrl(baseUrl).maxRetries(0).build());

        AIController created = (AIController) unsafe().allocateInstance(AIController.class);
        set(created, "aiService", ai);
        set(created, "repository", new TaskRepository("", "", "") {
            @Override public String getAllTasksAsText() { return "未完了のタスクはありません。"; }
        });
        set(created, "revenueContextService", new RevenueContextService(null, null) {
            @Override public String buildContext() { return "収益記録はありません。"; }
        });
        set(created, "activityRepository", new AiActivityRepository("", "", "") {
            @Override public long start(String agent, String action) { activities.add("start"); return 1; }
            @Override public void finish(long id, String status, String detail) { activities.add("finish " + status + " " + detail); }
        });
        set(created, "conversationRepository", repository());
        set(created, "writerJobRepository", new WriterJobRepository("", "", "") {
            @Override public Map<String, String> findLatestJob() { return Map.of(); }
        });
        set(created, "postDraftRepository", new PostDraftRepository("", "", "") {
            @Override public List<Map<String, String>> getDrafts() { return List.of(); }
        });
        set(created, "authorFactRepository", new AuthorFactRepository("", "", "") {
            @Override public List<Map<String, String>> getAllFacts() { return List.of(); }
        });
        return created;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
