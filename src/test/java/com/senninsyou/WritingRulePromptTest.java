package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;

// 書き方の決まりを、note記事と投稿案の下書きのAIへ渡すことを確かめる。
// 決まりがなければ指示文は今までと同じ。決まりが読めなくても下書きは止めず、決まりなしで作る。
// OpenAI の代わりに 127.0.0.1 の偽のサーバー（届いた指示文を記録する）、DB は偽物。本物の OpenAI と DB にはつながない。実行: mvn test
class WritingRulePromptTest {

    private static final String PROMPT_TAIL = "===== 返答形式 =====\n[POST]\n[END]\n";
    private static final String SECTION = "===== 書き方の決まり（本人のダメ出しやAIの指摘から残したもの） =====";

    private HttpServer server;
    private final List<String> prompts = new ArrayList<>();
    private final List<Object> ruleQueries = new ArrayList<>();
    private List<String> storedRules = List.of();
    private boolean rulesDown;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            prompts.add(new ObjectMapper().readTree(body).get("input").asText());
            byte[] bytes = message("[POST]\n今日の記録\n[INTENT]\n報告\n[END]").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 決まりがなければ指示文は変えない() {
        String prompt = "はじめ\n" + PROMPT_TAIL;
        assertSame(prompt, AIService.withWritingRules(prompt, ""));
        assertSame(prompt, AIService.withWritingRules(prompt, "  \n "));
        assertSame(prompt, AIService.withWritingRules(prompt, null));
    }

    @Test
    void 決まりは返答形式の直前に1回だけ足し_ほかは変えない() {
        String prompt = "はじめ\n===== 守る条件 =====\n・創作しない\n\n" + PROMPT_TAIL;
        String result = AIService.withWritingRules(prompt, "・一文を短くする\n・数字と日付を混ぜない\n");
        assertEquals("はじめ\n===== 守る条件 =====\n・創作しない\n\n"
                + SECTION + "\n"
                + "次の決まりを守ってください。ただし、上の条件と食い違うときは、上の条件を優先してください。\n"
                + "・一文を短くする\n・数字と日付を混ぜない\n\n"
                + PROMPT_TAIL, result);
    }

    @Test
    void 記事と投稿案の指示文には返答形式の見出しが1つだけある_ないか2つ以上なら足さずに止める() {
        AIService ai = new AIService();
        for (String prompt : List.of(
                ai.buildWriterPrompt("テーマ", "読者", "素材", "500円", "", ""),
                ai.buildPostPrompt("状況", "", ""))) {
            String result = AIService.withWritingRules(prompt, "・一文を短くする");
            assertEquals(1, count(result, SECTION));
            assertTrue(result.indexOf(SECTION) < result.indexOf(AIService.ANSWER_FORMAT_HEADING));
        }
        assertThrows(IllegalStateException.class, () -> AIService.withWritingRules("見出しなし", "・決まり"));
        assertThrows(IllegalStateException.class,
                () -> AIService.withWritingRules(PROMPT_TAIL + PROMPT_TAIL, "・決まり"));
    }

    @Test
    void 有効な決まりを対象と共通に絞って読み_1行に1つ並べる() {
        storedRules = List.of("一文を短くする", "数字と日付を混ぜない");
        WritingRuleRepository repository = new WritingRuleRepository(this::open);
        assertEquals("・一文を短くする\n・数字と日付を混ぜない", repository.activeRulesText("SNS投稿"));
        assertEquals(List.of(WritingRuleRepository.RULES_FOR_TARGET_SQL, "SNS投稿"), ruleQueries);
        assertTrue(WritingRuleRepository.RULES_FOR_TARGET_SQL.contains("WHERE active AND target IN ('共通', ?)"));

        storedRules = List.of();
        assertEquals("", repository.activeRulesText("note記事"));
    }

    @Test
    void 記事の下書きには_note記事と共通の決まりを渡す() throws Exception {
        storedRules = List.of("「やったこと」と「これからやること」を分けて書く");
        AIController controller = controllerWithFakes();
        Method writeDraft = AIController.class.getDeclaredMethod(
                "writeDraft", String.class, String.class, String.class, String.class, String.class);
        writeDraft.setAccessible(true);
        writeDraft.invoke(controller, "テーマ", "読者", "素材", "500円", "");

        assertEquals(1, prompts.size(), "AIは1回だけ呼ぶ");
        assertEquals("note記事", ruleQueries.get(1));
        assertTrue(prompts.get(0).contains(SECTION + "\n次の決まりを守ってください。ただし、上の条件と食い違うときは、上の条件を優先してください。\n"
                + "・「やったこと」と「これからやること」を分けて書く\n"), prompts.get(0));
        assertTrue(prompts.get(0).startsWith("あなたはProject千人将のnote記事制作を担当するライターAIです。"));
    }

    @Test
    void 投稿案には_SNS投稿と共通の決まりを渡す() throws Exception {
        storedRules = List.of("数字と日付を同じ文に混ぜない");
        PostController controller = postControllerWithFakes();
        Map<String, String> result = controller.generate(new PostController.GenerateRequest(""));

        assertEquals("1件の投稿案を作りました", result.get("status"));
        assertEquals(1, prompts.size(), "AIは1回だけ呼ぶ");
        assertEquals("SNS投稿", ruleQueries.get(1));
        assertTrue(prompts.get(0).contains(SECTION), prompts.get(0));
        assertTrue(prompts.get(0).contains("・数字と日付を同じ文に混ぜない\n\n" + AIService.ANSWER_FORMAT_HEADING), prompts.get(0));
    }

    @Test
    void 決まりがないときの投稿案の指示文は今までと同じ() throws Exception {
        storedRules = List.of();
        postControllerWithFakes().generate(new PostController.GenerateRequest(""));
        assertEquals(1, prompts.size());
        assertFalse(prompts.get(0).contains(SECTION));
        assertEquals(new AIService().buildPostPrompt(situation(), "", ""), prompts.get(0), "決まりがなければ今までと同じ指示文");
    }

    @Test
    void 決まりが読めなくても下書きは止めず_決まりなしで作り_接続先は出さない() throws Exception {
        rulesDown = true;
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Map<String, String> result;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            result = postControllerWithFakes().generate(new PostController.GenerateRequest(""));
        } finally {
            System.setOut(original);
        }
        assertEquals("1件の投稿案を作りました", result.get("status"));
        assertEquals(1, prompts.size(), "AIは1回だけ呼ぶ（やり直さない）");
        assertFalse(prompts.get(0).contains(SECTION));
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("書き方の決まりを読めなかったため、決まりなしで下書きを作ります（対象：SNS投稿）。"), out);
        assertFalse(out.contains("127.0.0.1:1"), "接続先を出さない");
    }

    // ===== 道具 =====

    private static String situation() {
        return """
                現在のタスク:
                未完了のタスクはありません。

                収益の状況:
                収益記録はありません。

                記憶している本人の事実の件数: 0件
                """;
    }

    private AIService fakeAi() throws Exception {
        AIService ai = new AIService();
        set(ai, "client", OpenAIOkHttpClient.builder().apiKey("test")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").maxRetries(0).build());
        return ai;
    }

    private AIController controllerWithFakes() throws Exception {
        AIController created = (AIController) unsafe().allocateInstance(AIController.class);
        set(created, "aiService", fakeAi());
        set(created, "authorFactRepository", new AuthorFactRepository("", "", "") {
            @Override public List<Map<String, String>> getAllFacts() { return List.of(); }
        });
        set(created, "writingRuleRepository", new WritingRuleRepository(this::open));
        return created;
    }

    private PostController postControllerWithFakes() throws Exception {
        PostController created = (PostController) unsafe().allocateInstance(PostController.class);
        set(created, "aiService", fakeAi());
        set(created, "repository", new PostDraftRepository("", "", "") {
            @Override public String buildRecentPostsText() { return ""; }
            @Override public void addDraft(String content, String intent) { }
        });
        set(created, "taskRepository", new TaskRepository("", "", "") {
            @Override public String getAllTasksAsText() { return "未完了のタスクはありません。"; }
        });
        set(created, "revenueContextService", new RevenueContextService(null, null) {
            @Override public String buildContext() { return "収益記録はありません。"; }
        });
        set(created, "authorFactRepository", new AuthorFactRepository("", "", "") {
            @Override public List<Map<String, String>> getAllFacts() { return List.of(); }
        });
        set(created, "activityRepository", new AiActivityRepository("", "", "") {
            @Override public long start(String agent, String action) { return 1; }
            @Override public void finish(long id, String status, String detail) { }
        });
        set(created, "writingRuleRepository", new WritingRuleRepository(this::open));
        return created;
    }

    // 書き方の決まりの偽のDB。読んだSQLと条件の値を ruleQueries に残し、storedRules を返す。
    private Connection open() throws SQLException {
        if (rulesDown) {
            throw new SQLException("Connection to 127.0.0.1:1 refused");
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        ruleQueries.add(args[0]);
                        yield statement();
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PreparedStatement statement() {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString" -> {
                        ruleQueries.add(args[1]);
                        yield null;
                    }
                    case "executeQuery" -> resultSet(storedRules);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet resultSet(List<String> rules) {
        int[] index = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++index[0] < rules.size();
                    case "getString" -> rules.get(index[0]);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }

    private static String message(String text) {
        return "{\"id\":\"resp_test\",\"object\":\"response\",\"created_at\":0,\"status\":\"completed\",\"model\":\"gpt-5.2\","
                + "\"output\":[{\"type\":\"message\",\"id\":\"msg_test\",\"status\":\"completed\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":" + quote(text) + ",\"annotations\":[]}]}]}";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
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
