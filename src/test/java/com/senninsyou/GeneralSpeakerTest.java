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

// ハル・ガクの話者切り替え（AI将軍の1回の返答の中で話者を分ける）を確かめる。
// OpenAI の代わりに、この端末の中だけで動く偽のサーバーが返答を返す。本物の OpenAI と DB にはつながない。
// AIController と AIService は本物のコードを使い、DB の部品だけを記録用の偽物に差し替える。実行: mvn test
class GeneralSpeakerTest {

    private HttpServer server;
    private volatile String body;
    private volatile String responseStatus = "completed";
    private final List<String> requests = new ArrayList<>();
    private final List<String> activities = new ArrayList<>();
    private final List<String> conversations = new ArrayList<>();
    private AIController controller;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = ("{\"id\":\"resp_test\",\"object\":\"response\",\"created_at\":0,\"status\":\"" + responseStatus + "\","
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

    // ===== J3：正しい組み合わせ4通りは成功し、会話記録の本文は話者つき（J5） =====

    @Test
    void ハルだけ() {
        body = answer("ハル", "価格を決めましょう。", "", "", "");
        String result = controller.command(request("discuss"));
        assertTrue(result.contains("\"speaker\":\"ハル\""));
        assertSucceeded("ハル：価格を決めましょう。");
    }

    @Test
    void ガクだけ() {
        body = answer("ガク", "", "まず計測を入れます。", "", "");
        controller.command(request("discuss"));
        assertSucceeded("ガク：まず計測を入れます。");
    }

    @Test
    void 両方はハルからガクの順で残す() {
        body = answer("両方", "小さく売ります。", "決済は手作業で始めます。", "", "");
        controller.command(request("discuss"));
        assertSucceeded("ハル：小さく売ります。\nガク：決済は手作業で始めます。");
    }

    @Test
    void 確認は1問だけで_AI将軍_確認_として残す() {
        body = answer("確認", "", "", "売上を増やしたいですか、作業を減らしたいですか？", "");
        String result = controller.command(request("discuss"));
        assertTrue(result.contains("\"action\":\"\""), "確認では操作を出さない");
        assertSucceeded("AI将軍（確認）：売上を増やしたいですか、作業を減らしたいですか？");
    }

    @Test
    void 両方で操作の提案があれば_今までどおり提案として返る() {
        body = answer("両方", "記事を出しましょう。", "下書きはライターAIに任せます。", "", "startWriting");
        String result = controller.command(request("discuss"));
        assertTrue(result.contains("\"action\":\"startWriting\""), "提案が返るだけで、実行は画面で承認されてから");
        assertSucceeded("ハル：記事を出しましょう。\nガク：下書きはライターAIに任せます。");
    }

    // ===== J3b：speakerReason は補足。空でも、組み合わせが正しければ成功 =====

    @Test
    void 話者の理由が空でも成功() {
        body = answer("ハル", "価格を決めましょう。", "", "", "").replace("\"speakerReason\":\"目的から判断\"", "\"speakerReason\":\"\"");
        controller.command(request("discuss"));
        assertSucceeded("ハル：価格を決めましょう。");
    }

    // ===== J3・J4：外れた組み合わせはすべて失敗。状態値は「失敗」、理由は詳細欄。作り直しの呼び出しはしない =====

    @Test
    void ハルなのにガクの発言がある() { assertMismatch(answer("ハル", "価格です。", "技術です。", "", "")); }

    @Test
    void ガクなのにハルの発言がある() { assertMismatch(answer("ガク", "価格です。", "技術です。", "", "")); }

    @Test
    void 両方なのに片方が空() { assertMismatch(answer("両方", "価格です。", "", "", "")); }

    @Test
    void 確認なのにハルの発言がある() { assertMismatch(answer("確認", "価格です。", "", "どちらですか？", "")); }

    @Test
    void 確認なのに操作の提案がある() { assertMismatch(answer("確認", "", "", "どちらですか？", "runQuality")); }

    @Test
    void ハルなのに発言が空() { assertMismatch(answer("ハル", "", "", "", "")); }

    @Test
    void 確認なのに質問が空() { assertMismatch(answer("確認", "", "", "", "")); }

    @Test
    void ハルなのに確認の質問もある() { assertMismatch(answer("ハル", "価格です。", "", "どちらですか？", "")); }

    @Test
    void 結論が空() { assertMismatch(answer("ハル", "価格です。", "", "", "").replace("\"summary\":\"結論です。\"", "\"summary\":\"\"")); }

    @Test
    void 話者が決められた値でない() { assertMismatch(answer("AI将軍", "価格です。", "", "", "")); }

    @Test
    void 話者の項目がない古い形の返答も失敗() {
        assertMismatch("{\"summary\":\"結論です。\",\"revenueInsight\":\"\",\"performanceDecision\":\"\",\"marketOpportunity\":\"\","
                + "\"nextTask\":\"\",\"priority\":\"\",\"assignedAgent\":\"\",\"action\":\"\",\"actionLabel\":\"\","
                + "\"actionTheme\":\"\",\"actionAudience\":\"\"}");
    }

    // ===== J4b：完了していない返答は、相談でもタスク案の整理でも受け取らない =====

    @Test
    void 相談で完了していない返答は_話者が正しくても失敗() {
        responseStatus = "incomplete";
        body = answer("ハル", "価格を決めましょう。", "", "", "");
        assertEquals("AI将軍から有効な返答を受け取れませんでした。", controller.command(request("discuss")));
        assertEquals(List.of("start", "finish 失敗 返答を受け取れませんでした"), activities);
        assertEquals(List.of(), conversations);
        assertEquals(1, requests.size());
    }

    @Test
    void タスク案の整理で完了していない返答は失敗() {
        responseStatus = "incomplete";
        body = proposeAnswer();
        assertEquals("AI将軍から有効な返答を受け取れませんでした。", controller.command(request("propose")));
        assertEquals(List.of("start", "finish 失敗 返答を受け取れませんでした"), activities);
        assertEquals(1, requests.size());
    }

    // ===== J1・J7：相談は16項目の形、タスク案の整理は11項目の形で依頼し、タスク案はAI将軍のまま =====

    @Test
    void 相談は話者つきの形で1回だけ依頼する() {
        body = answer("ハル", "価格を決めましょう。", "", "", "");
        controller.command(request("discuss"));
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).contains("\"name\":\"general_consult_answer\""));
        assertTrue(requests.get(0).contains("\"speaker\""));
    }

    @Test
    void タスク案の整理は今までの形で依頼し_AI将軍として要約を残す() {
        body = proposeAnswer();
        String result = controller.command(request("propose"));
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).contains("\"name\":\"general_answer\""));
        assertFalse(requests.get(0).contains("\"speaker\""), "タスク案の整理の形に話者の項目はない");
        assertTrue(result.contains("\"nextTask\":\"採点を終える\""));
        assertEquals(List.of("start", "finish 完了 "), activities);
        assertEquals(List.of("AI将軍：タスク案です。"), conversations, "タスク案の整理では利用者の発言は残さず、AI将軍の要約だけ");
    }

    private void assertSucceeded(String savedText) {
        assertEquals(1, requests.size(), "呼び出しは1回だけ");
        assertEquals(List.of("start", "finish 完了 "), activities);
        assertEquals(List.of("利用者：相談です", "AI将軍：" + savedText), conversations, "AI側は「AI将軍」として1件、本文は話者つき");
    }

    private void assertMismatch(String answer) {
        body = answer;
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> controller.command(request("discuss")));
        assertTrue(error.getMessage().contains("返答形式が正しくありません"));
        assertEquals(1, requests.size(), "作り直しの呼び出しはしない");
        assertEquals(List.of("start", "finish 失敗 話者と発言の組み合わせが正しくありませんでした"), activities,
                "状態値は「失敗」のまま、理由は詳細欄");
        assertEquals(List.of(), conversations, "失敗した相談は会話記録に残さない");
    }

    private static String answer(String speaker, String haru, String gaku, String clarify, String action) {
        return "{\"summary\":\"結論です。\",\"revenueInsight\":\"\",\"performanceDecision\":\"\",\"marketOpportunity\":\"\","
                + "\"nextTask\":\"\",\"priority\":\"\",\"assignedAgent\":\"\",\"action\":\"" + action + "\","
                + "\"actionLabel\":\"" + (action.isEmpty() ? "" : "記事を書きますか？") + "\","
                + "\"actionTheme\":\"" + (action.equals("startWriting") ? "テーマ" : "") + "\","
                + "\"actionAudience\":\"" + (action.equals("startWriting") ? "読者" : "") + "\","
                + "\"speaker\":\"" + speaker + "\",\"speakerReason\":\"目的から判断\",\"haruText\":\"" + haru + "\","
                + "\"gakuText\":\"" + gaku + "\",\"clarifyQuestion\":\"" + clarify + "\"}";
    }

    private static String proposeAnswer() {
        return "{\"summary\":\"タスク案です。\",\"revenueInsight\":\"\",\"performanceDecision\":\"\",\"marketOpportunity\":\"\","
                + "\"nextTask\":\"採点を終える\",\"priority\":\"中\",\"assignedAgent\":\"品質管理AI\",\"action\":\"\","
                + "\"actionLabel\":\"\",\"actionTheme\":\"\",\"actionAudience\":\"\"}";
    }

    private static AIController.CommandRequest request(String mode) {
        return new AIController.CommandRequest("相談です", mode.equals("propose") ? "利用者: これまでの相談" : "", mode);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
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
            @Override public void finish(long id, String status, String detail) { activities.add("finish " + status + " " + detail); }
        });
        set(created, "conversationRepository", new ConversationRepository("", "", "") {
            @Override public void add(String role, String message) { conversations.add(role + "：" + message); }
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
