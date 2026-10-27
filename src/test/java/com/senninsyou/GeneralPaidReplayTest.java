package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;

// 2026-10-26 の有料確認（GPT-5.2、実行 20261026-234619-58508）で実際に返ってきた返答本文を、追加の API 呼び出しなしで
// 本物の AIController の検証に通し直す。返答本文は src/test/resources/com/senninsyou/paid-check-20261026/case-<項目>.json。
// これは修正前のプロンプトで得た返答なので、プロンプト修正の効果は表さない。検証の動き（何を受け付け、何を拒否するか）を固定する。
//   不合格だった4件（2・5・6・9）は、話者がハルかガクなのに clarifyQuestion にも質問が入っていたため拒否された。
//   合格した相談5件（1・3・4・7・8）とタスク案のまとめ（10）は受け付けられる。実行: mvn test
class GeneralPaidReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HISTORY = "利用者: 新しいオンライン講座を始めたい\nAI将軍: ハル：まず小さく試して反応を見るのがよいです。\nガク：今の仕組みで受付と配信はできます。";

    private HttpServer server;
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
            byte[] bytes = ("{\"id\":\"resp_test\",\"object\":\"response\",\"created_at\":0,\"status\":\"completed\","
                    + "\"model\":\"gpt-5.2\",\"output\":[{\"type\":\"message\",\"id\":\"msg_test\",\"status\":\"completed\","
                    + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":" + JSON.writeValueAsString(body) + ",\"annotations\":[]}]}]}")
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

    // ===== 不合格だった4件：話者はハル・ガクで正しいが、clarifyQuestion にも質問がある。補正せず失敗にする =====

    @Test
    void 項目2_技術だけ_ガクの回答に確認の質問が付いていたので拒否() {
        assertRejectedOnlyForClarify("2", "ガク", "APIの応答が遅いとき、原因を調べる順番を教えて");
    }

    @Test
    void 項目5_技術の話でハルを指名_ハルの回答に確認の質問が付いていたので拒否() {
        assertRejectedOnlyForClarify("5", "ハル", "ハルに聞きたい。APIの応答が遅いとき、原因を調べる順番は？");
    }

    @Test
    void 項目6_経営の話でガクを指名_ガクの回答に確認の質問が付いていたので拒否() {
        assertRejectedOnlyForClarify("6", "ガク", "ガクに聞きたい。新しいオンライン講座の価格は、どう決めるべき？");
    }

    @Test
    void 項目9_存在しない操作_ハルの回答に確認の質問が付いていたので拒否_公開の操作は出していない() {
        assertRejectedOnlyForClarify("9", "ハル", "書いた記事をnoteに公開して");
        assertEquals("", saved("9").path("action").asText(), "公開の操作は存在しないので、提案もしていない");
    }

    // ===== 合格した相談5件：話者の選び方どおりに受け付け、会話記録は話者つきの本文 =====

    @Test
    void 項目1_経営だけはハル() { assertAccepted("1", "新しいオンライン講座の価格は、どう決めるべき？", "ハル", ""); }

    @Test
    void 項目3_明確に両方は両方() { assertAccepted("3", "月額サービスを作るとき、料金設計とログイン・データ分離をどう考えるべき？", "両方", ""); }

    @Test
    void 項目4_曖昧は確認の1問だけで操作なし() { assertAccepted("4", "あれ、どうしたらいい？", "確認", ""); }

    @Test
    void 項目7_両方を指名は両方() { assertAccepted("7", "ハルとガクの両方に聞きたい。新しいオンライン講座を始めるべき？", "両方", ""); }

    @Test
    void 項目8_操作の依頼は承認が必要な提案として返る() { assertAccepted("8", "直近の記事を品質管理AIに採点させて", "ガク", "runQuality"); }

    // ===== タスク案のまとめ：11項目のまま、話者の項目はない =====

    @Test
    void 項目10_タスク案のまとめは11項目で話者なし() throws Exception {
        body = savedText("10");
        String result = controller.command(new AIController.CommandRequest("これまで話した内容を踏まえ、登録前に確認するタスク案を1件まとめて。", HISTORY, "propose"));
        JsonNode answer = JSON.readTree(result);
        assertEquals(11, answer.size());
        assertFalse(answer.has("speaker"));
        assertFalse(answer.path("nextTask").asText().isBlank());
        assertEquals(List.of("start", "finish 完了 "), activities);
        assertEquals(1, requests.size());
    }

    // 拒否の理由が「ハル・ガクの返答に clarifyQuestion が入っていたこと」だけであることを、保存データで確かめる。
    // （試験の中で clarifyQuestion を空にした写しは受け付けられる。本番のコードに補正は入れない。）
    private void assertRejectedOnlyForClarify(String caseId, String speaker, String message) {
        JsonNode answer = saved(caseId);
        assertEquals(speaker, answer.path("speaker").asText(), "話者の選び方は期待どおりだった");
        assertFalse(answer.path(speaker.equals("ハル") ? "haruText" : "gakuText").asText().isBlank(), "話者の発言はある");
        assertFalse(answer.path("clarifyQuestion").asText().isBlank(), "確認ではないのに、確認の質問も入っていた");

        body = savedText(caseId);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.command(new AIController.CommandRequest(message, "", "discuss")));
        assertTrue(error.getMessage().contains("返答形式が正しくありません"));
        assertEquals(List.of("start", "finish 失敗 話者と発言の組み合わせが正しくありませんでした"), activities, "既存の失敗の流れ（補正しない）");
        assertEquals(List.of(), conversations, "失敗した相談は会話記録に残さない");
        assertEquals(1, requests.size(), "作り直しの呼び出しはしない");

        ObjectNode withoutClarify = (ObjectNode) answer.deepCopy();
        withoutClarify.put("clarifyQuestion", "");
        assertEquals(speaker, AIControllerSpeakerCheck.accepts(withoutClarify), "clarifyQuestion を空にした写しなら組み合わせは正しい");
    }

    private void assertAccepted(String caseId, String message, String speaker, String action) {
        body = savedText(caseId);
        String result = controller.command(new AIController.CommandRequest(message, "", "discuss"));
        JsonNode answer = readTree(result);
        assertEquals(speaker, answer.path("speaker").asText());
        assertEquals(action, answer.path("action").asText());
        assertEquals(List.of("start", "finish 完了 "), activities);
        assertEquals(2, conversations.size(), "利用者の発言と、AI将軍として1件");
        assertTrue(conversations.get(1).startsWith(speaker.equals("確認") ? "AI将軍：AI将軍（確認）：" : speaker.equals("ガク") ? "AI将軍：ガク：" : "AI将軍：ハル："));
        assertEquals(1, requests.size());
    }

    private static JsonNode saved(String caseId) { return readTree(savedText(caseId)); }

    private static String savedText(String caseId) {
        try (InputStream in = GeneralPaidReplayTest.class.getResourceAsStream("paid-check-20261026/case-" + caseId + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("保存した返答を読めません: " + caseId, e);
        }
    }

    private static JsonNode readTree(String text) {
        try { return JSON.readTree(text); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // AIController.checkSpeakers（同じパッケージから使える）で、組み合わせが正しいかだけを確かめる。
    static class AIControllerSpeakerCheck {
        static String accepts(ObjectNode answer) {
            AIController.checkSpeakers(answer);
            return answer.path("speaker").asText();
        }
    }

    // 本物の AIController を、DB につながない偽の部品で組み立てる（コンストラクタは DATABASE_URL を読むので通さない）。
    // 有料確認と同じ固定の試験データ（直近の記事は「試験用の記事」）を使う。
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
            @Override public Map<String, String> findLatestJob() { return Map.of("id", "1", "status", "完了", "title", "試験用の記事"); }
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
