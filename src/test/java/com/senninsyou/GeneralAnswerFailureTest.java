package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;

// AI将軍の返答が空・途中で終了・拒否・取得失敗のときに、成功扱いにならず、操作も提案されないことを確かめる。
// OpenAI の代わりに、この端末の中だけで動く偽のサーバーが返答を返す。本物の OpenAI と DB にはつながない。
// AIController と AIService は本物のコードを使い、DB の部品だけを記録用の偽物に差し替える。実行: mvn test
class GeneralAnswerFailureTest {

    private HttpServer server;
    private volatile int status;
    private volatile String body;
    private final List<String> requests = new ArrayList<>();
    private final List<String> activities = new ArrayList<>();
    private final List<String> conversations = new ArrayList<>();
    private AIController controller;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
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
    void 正常なJSONは成功として返り_依頼には厳格な形が付いている() {
        answer(200, message(outputText(json("今の状況です。", ""))));

        String result = controller.command(request());

        assertTrue(result.contains("\"summary\":\"今の状況です。\""));
        assertEquals(List.of("start", "finish 完了"), activities);
        assertEquals(List.of("利用者", "AI将軍"), conversations);
        assertTrue(requests.get(0).contains("\"type\":\"json_schema\""), "送る依頼に返答の形が入っている");
        assertTrue(requests.get(0).contains("\"strict\":true"));
    }

    @Test
    void 返答の文が空なら失敗() {
        answer(200, message(outputText("")));
        assertFailedWithoutAnswer();
    }

    @Test
    void 返答に文がひとつもなければ失敗() {
        answer(200, response("completed", "[]"));
        assertFailedWithoutAnswer();
    }

    @Test
    void 正常に完了した返答の操作の提案は従来どおり返る() {
        // 提案が返るだけで、実行は画面で承認されてから。
        answer(200, message(outputText(json("品質管理AIに採点させます。", "runQuality"))));

        String result = controller.command(request());

        assertTrue(result.contains("\"action\":\"runQuality\""));
        assertEquals(List.of("start", "finish 完了"), activities);
        assertEquals(List.of("利用者", "AI将軍"), conversations);
    }

    @Test
    void 途中で終わった返答は_JSONが正常で操作の提案があっても失敗() {
        // 10/15 に見つけた抜け道の回帰テスト。上限などで途中終了したのに、文はJSONとして読めてしまう場合。
        answer(200, response("incomplete", "[" + messageItem(outputText(json("品質管理AIに採点させます。", "runQuality"))) + "]"));
        assertFailedWithoutAnswer();
    }

    @Test
    void 途中で切れた返答は失敗() {
        // 上限に達して途中で切れた返答。操作の提案を書きかけたところで終わっている。
        String cut = "{\"summary\":\"記事を書きます\",\"action\":\"startWriting\",\"actionLabel\":\"執筆";
        answer(200, response("incomplete", "[" + messageItem(outputText(cut)) + "]"));
        assertFailedWithoutAnswer();
    }

    @Test
    void 状態が分からない返答は_JSONが正常でも失敗() {
        answer(200, "{\"id\":\"resp_test\",\"object\":\"response\",\"created_at\":0,\"model\":\"gpt-5.2\",\"output\":["
                + messageItem(outputText(json("品質管理AIに採点させます。", "runQuality"))) + "]}");
        assertFailedWithoutAnswer();
    }

    @Test
    void 状態が失敗の返答は失敗() {
        answer(200, response("failed", "[" + messageItem(outputText(json("今の状況です。", ""))) + "]"));
        assertFailedWithoutAnswer();
    }

    @Test
    void 完了していてもJSONが壊れていれば形式エラーとして失敗() {
        String broken = "{\"summary\":\"記事を書きます\",\"action\":\"startWriting\"";
        answer(200, message(outputText(broken)));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> controller.command(request()));

        assertTrue(error.getMessage().contains("返答形式が正しくありません"));
        assertEquals(List.of("start", "finish 失敗"), activities);
        assertEquals(List.of(), conversations, "失敗した相談は会話記録に残さない");
    }

    @Test
    void 拒否された返答は失敗() {
        answer(200, message("{\"type\":\"refusal\",\"refusal\":\"お手伝いできません。\"}"));
        assertFailedWithoutAnswer();
    }

    @Test
    void サーバーエラーなら失敗() {
        answer(500, "{\"error\":{\"message\":\"server error\",\"type\":\"server_error\"}}");
        assertFailedWithoutAnswer();
    }

    @Test
    void 利用上限のエラーなら失敗() {
        answer(429, "{\"error\":{\"message\":\"rate limit\",\"type\":\"rate_limit_exceeded\"}}");
        assertFailedWithoutAnswer();
    }

    @Test
    void つながらなければ失敗() {
        server.stop(0);
        server = null;
        assertFailedWithoutAnswer();
    }

    // 失敗の返答は画面で JSON として読めないので、操作の承認カードは出ない（app.js の sendCommand は例外として扱う）。
    private void assertFailedWithoutAnswer() {
        String result = controller.command(request());

        assertEquals("AI将軍から有効な返答を受け取れませんでした。", result);
        assertFalse(result.contains("action"), "操作の提案を含まない");
        assertEquals(List.of("start", "finish 失敗"), activities);
        assertEquals(List.of(), conversations, "失敗した相談は会話記録に残さない");
    }

    private void answer(int status, String body) {
        this.status = status;
        this.body = body;
    }

    private static AIController.CommandRequest request() {
        return new AIController.CommandRequest("今の状況は？", "", "discuss");
    }

    private static String json(String summary, String action) {
        return "{\"summary\":\"" + summary + "\",\"revenueInsight\":\"\",\"performanceDecision\":\"\","
                + "\"marketOpportunity\":\"\",\"nextTask\":\"\",\"priority\":\"\",\"assignedAgent\":\"\","
                + "\"action\":\"" + action + "\",\"actionLabel\":\"\",\"actionTheme\":\"\",\"actionAudience\":\"\"}";
    }

    private static String outputText(String text) {
        return "{\"type\":\"output_text\",\"text\":" + quote(text) + ",\"annotations\":[]}";
    }

    private static String messageItem(String content) {
        return "{\"type\":\"message\",\"id\":\"msg_test\",\"status\":\"completed\",\"role\":\"assistant\","
                + "\"content\":[" + content + "]}";
    }

    private static String message(String content) {
        return response("completed", "[" + messageItem(content) + "]");
    }

    private static String response(String status, String output) {
        return "{\"id\":\"resp_test\",\"object\":\"response\",\"created_at\":0,\"status\":\"" + status + "\","
                + "\"model\":\"gpt-5.2\",\"output\":" + output + "}";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // 本物の AIController を、DB につながない偽の部品で組み立てる（コンストラクタは DATABASE_URL を読むので通さない）。
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
            @Override public void finish(long id, String status, String detail) { activities.add("finish " + status); }
        });
        set(created, "conversationRepository", new ConversationRepository("", "", "") {
            @Override public void add(String role, String message) { conversations.add(role); }
        });
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
