package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;

// 相談（/api/ai/command）が失敗したときの応答の形を確かめる。
// 失敗なのに、画面が JSON として読めない文章を 200 で返すと、画面は解析エラーになる（2026-11-21 の送信エラー）。
// 失敗は、HTTP の失敗の状態と、JSON の {error, errorCode} で返す。成功の返答は今までどおり。
// Spring が失敗を応答に変えるのと同じく、AIController の @ExceptionHandler のうち、投げられた例外を受け持つものを探して呼ぶ。
// OpenAI の代わりに 127.0.0.1 の偽のサーバー、DB の部品は記録用の偽物。本物の OpenAI と DB にはつながない。実行: mvn test
class CommandFailureResponseTest {

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body;
    private final List<String> requests = new ArrayList<>();
    private final List<String> activities = new ArrayList<>();
    private final List<String> conversations = new ArrayList<>();
    private volatile boolean databaseDown;
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
    void AIにつながらないときは_502とJSONの失敗を返す() throws Exception {
        server.stop(0);
        server = null;
        Failure failure = failureOf(request("discuss"));
        assertFailure(failure, 502, "AI_NO_ANSWER", "AI将軍から有効な返答を受け取れませんでした。");
        assertEquals(List.of("start", "finish 失敗 返答を受け取れませんでした"), activities, "実行ログは今までどおり");
        assertEquals(List.of(), conversations, "失敗した相談は会話記録に残さない");
    }

    @Test
    void AIがエラーを返したときも_502とJSONの失敗を返し_AIは1回だけ呼ぶ() throws Exception {
        status = 500;
        body = "{\"error\":{\"message\":\"server error\",\"type\":\"server_error\"}}";
        Failure failure = failureOf(request("discuss"));
        assertFailure(failure, 502, "AI_NO_ANSWER", "AI将軍から有効な返答を受け取れませんでした。");
        assertEquals(1, requests.size(), "自動で呼び直さない");
    }

    @Test
    void 完了していない返答も_502とJSONの失敗を返す_タスク案の整理でも同じ() throws Exception {
        body = response("incomplete", "[]");
        assertFailure(failureOf(request("discuss")), 502, "AI_NO_ANSWER", "AI将軍から有効な返答を受け取れませんでした。");
        assertFailure(failureOf(request("propose")), 502, "AI_NO_ANSWER", "AI将軍から有効な返答を受け取れませんでした。");
        assertEquals(2, requests.size(), "それぞれ1回だけ");
    }

    @Test
    void DBにつながらないときは_AIを呼ばずに503とJSONの失敗を返す() throws Exception {
        databaseDown = true;
        Failure failure = failureOf(request("discuss"));
        assertFailure(failure, 503, "DB_UNAVAILABLE", "AI将軍を起動できません。データベース接続を確認してください。");
        assertEquals(List.of(), requests, "AIは呼ばない");
        assertEquals(List.of(), activities, "実行ログは今までどおり（始めない）");
    }

    @Test
    void 返答の形式が正しくないときも_502とJSONの失敗を返す() throws Exception {
        body = message("{\"summary\":\"記事を書きます\",\"action\":\"startWriting\"");
        Failure failure = failureOf(request("discuss"));
        assertTrue(failure.thrown() instanceof IllegalStateException, "今までどおり IllegalStateException の仲間");
        assertFailure(failure, 502, "AI_BAD_FORMAT", "AI将軍の返答形式が正しくありません。");
        assertEquals(List.of("start", "finish 失敗 返答の形式が正しくありませんでした"), activities);
    }

    @Test
    void 成功の返答は今までどおりJSONの文で返す() throws Exception {
        body = message(json("今の状況です。"));
        String result = controller.command(request("discuss"));
        JsonNode node = new ObjectMapper().readTree(result);
        assertEquals("今の状況です。", node.path("summary").asText());
        assertEquals(List.of("start", "finish 完了 "), activities);
    }

    // ===== 失敗を Spring と同じく応答に変える =====

    private record Failure(RuntimeException thrown, ResponseEntity<?> response) {}

    private Failure failureOf(AIController.CommandRequest request) throws Exception {
        String returned = null;
        RuntimeException thrown = null;
        try {
            returned = controller.command(request);
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertNull(returned, "失敗なのに、文章を成功（200）として返した（画面は JSON として読めない）：" + returned);
        RuntimeException error = thrown;
        Method handler = Arrays.stream(AIController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                .filter(method -> Arrays.stream(handledTypes(method)).anyMatch(type -> type.isInstance(error)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("この失敗を応答に変える @ExceptionHandler がない：" + error));
        handler.setAccessible(true);
        try {
            return new Failure(error, (ResponseEntity<?>) handler.invoke(controller, error));
        } catch (InvocationTargetException e) {
            throw new AssertionError("失敗を応答に変える処理が例外を投げた", e.getCause());
        }
    }

    private static Class<?>[] handledTypes(Method method) {
        Class<? extends Throwable>[] declared = method.getAnnotation(ExceptionHandler.class).value();
        if (declared.length > 0) return declared;
        return Arrays.stream(method.getParameterTypes()).filter(Throwable.class::isAssignableFrom).toArray(Class<?>[]::new);
    }

    private static void assertFailure(Failure failure, int httpStatus, String errorCode, String message) {
        ResponseEntity<?> response = failure.response();
        assertEquals(httpStatus, response.getStatusCode().value(), "HTTP の状態");
        MediaType type = response.getHeaders().getContentType();
        assertNotNull(type, "Content-Type がある");
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(type), "JSON で返す：" + type);
        assertTrue(response.getBody() instanceof Map<?, ?>, "本文は {error, errorCode}：" + response.getBody());
        Map<?, ?> json = (Map<?, ?>) response.getBody();
        assertEquals(message, json.get("error"));
        assertEquals(errorCode, json.get("errorCode"));
        assertEquals(2, json.size(), "ほかの項目（操作の提案など）を含まない");
    }

    // ===== 偽の部品 =====

    private static AIController.CommandRequest request(String mode) {
        return new AIController.CommandRequest("今の状況は？", mode.equals("propose") ? "利用者: これまでの相談" : "", mode);
    }

    private static String json(String summary) {
        return "{\"summary\":\"" + summary + "\",\"revenueInsight\":\"\",\"performanceDecision\":\"\","
                + "\"marketOpportunity\":\"\",\"nextTask\":\"\",\"priority\":\"\",\"assignedAgent\":\"\","
                + "\"action\":\"\",\"actionLabel\":\"\",\"actionTheme\":\"\",\"actionAudience\":\"\","
                + "\"speaker\":\"ハル\",\"speakerReason\":\"事業の判断\",\"haruText\":\"" + summary + "\","
                + "\"gakuText\":\"\",\"clarifyQuestion\":\"\"}";
    }

    private static String message(String text) {
        return response("completed", "[{\"type\":\"message\",\"id\":\"msg_test\",\"status\":\"completed\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":" + quote(text) + ",\"annotations\":[]}]}]");
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
            @Override public String getAllTasksAsText() {
                return databaseDown ? "データベース接続に失敗しています。" : "未完了のタスクはありません。";
            }
        });
        set(created, "revenueContextService", new RevenueContextService(null, null) {
            @Override public String buildContext() { return "収益記録はありません。"; }
        });
        set(created, "activityRepository", new AiActivityRepository("", "", "") {
            @Override public long start(String agent, String action) { activities.add("start"); return 1; }
            @Override public void finish(long id, String status, String detail) { activities.add("finish " + status + " " + detail); }
        });
        set(created, "conversationRepository", new ConversationRepository("", "", "") {
            @Override public void add(String role, String message) { conversations.add(role); }
            @Override public ExchangeSave addExchange(String userMessage, String aiMessage) {
                conversations.add("利用者");
                conversations.add("AI将軍");
                return ExchangeSave.SAVED;
            }
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
