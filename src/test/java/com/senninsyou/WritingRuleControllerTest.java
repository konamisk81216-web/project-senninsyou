package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;

// 書き方の決まり（/api/writing-rules）の一覧・追加・編集・有効と無効の切り替えを確かめる。
// DBは、SQLと渡した値を記録する偽物。本番DBにはつながない。実行: mvn test
// 失敗は、Spring と同じく @ExceptionHandler のうち、投げられた例外を受け持つものを探して呼び、状態と JSON を確かめる。
class WritingRuleControllerTest {

    // 偽のDBへの1回の依頼（SQLと、渡した値）。
    record Call(String sql, List<Object> values) {
    }

    private final List<Call> calls = new ArrayList<>();
    private List<Object[]> rows = List.of();
    private int updatedRows = 1;
    private long returnedId = 42;
    private boolean down;

    private WritingRuleController controller() {
        return new WritingRuleController(new WritingRuleRepository(this::open));
    }

    @Test
    void 一覧は有効な決まりを先に_列をそのまま返す() {
        rows = List.<Object[]>of(
                new Object[] {7L, "SNS投稿", "数字と日付を混ぜない", "AIが日付を数字と取り違えた", "AIの指摘", true},
                new Object[] {3L, "共通", "「やったこと」と「これからやること」を分ける", "", "本人", false});
        List<Map<String, Object>> list = controller().list();
        assertEquals(1, calls.size());
        assertEquals(WritingRuleRepository.LIST_SQL, calls.get(0).sql());
        assertTrue(calls.get(0).sql().contains("ORDER BY active DESC, id DESC"), "有効な決まりを先に、新しい順");
        assertEquals(2, list.size());
        assertEquals(Map.of("id", 7L, "target", "SNS投稿", "rule", "数字と日付を混ぜない",
                "reason", "AIが日付を数字と取り違えた", "source", "AIの指摘", "active", true), list.get(0));
        assertEquals(false, list.get(1).get("active"));
    }

    @Test
    void 追加は201と番号を返し_前後の空白を除いて保存する() {
        ResponseEntity<Map<String, Object>> response = controller().add(
                new WritingRuleController.RuleRequest("note記事", "  題名に試した日数を正しく書く \n", "  1週間と書いたが2日だった ", "本人"));
        assertEquals(201, response.getStatusCode().value());
        assertEquals(42L, response.getBody().get("id"));
        assertEquals(1, calls.size());
        assertEquals(WritingRuleRepository.INSERT_SQL, calls.get(0).sql());
        assertEquals(List.of("note記事", "題名に試した日数を正しく書く", "1週間と書いたが2日だった", "本人"), calls.get(0).values());
    }

    @Test
    void きっかけは空でも追加できる_決まりは500文字ちょうどまで() {
        String rule = "あ".repeat(WritingRuleController.MAX_RULE_LENGTH);
        controller().add(new WritingRuleController.RuleRequest("共通", rule, null, "AIの指摘"));
        assertEquals(List.of("共通", rule, "", "AIの指摘"), calls.get(0).values());
    }

    @Test
    void 入力の誤りは400で返し_DBには触れない() {
        WritingRuleController controller = controller();
        String longRule = "あ".repeat(WritingRuleController.MAX_RULE_LENGTH + 1);
        String longReason = "い".repeat(WritingRuleController.MAX_REASON_LENGTH + 1);
        List<Object[]> cases = List.<Object[]>of(
                new Object[] {new WritingRuleController.RuleRequest("共通", "   ", "", "本人"), "決まりを入力してください。"},
                new Object[] {new WritingRuleController.RuleRequest("共通", null, "", "本人"), "決まりを入力してください。"},
                new Object[] {new WritingRuleController.RuleRequest("共通", longRule, "", "本人"), "決まりは500文字以内にしてください。"},
                new Object[] {new WritingRuleController.RuleRequest("共通", "短く書く", longReason, "本人"), "きっかけは1000文字以内にしてください。"},
                new Object[] {new WritingRuleController.RuleRequest("ブログ", "短く書く", "", "本人"), "対象は「共通」「note記事」「SNS投稿」から選んでください。"},
                new Object[] {new WritingRuleController.RuleRequest(null, "短く書く", "", "本人"), "対象は「共通」「note記事」「SNS投稿」から選んでください。"},
                new Object[] {new WritingRuleController.RuleRequest("共通", "短く書く", "", "Gemini"), "きっかけの種類は「本人」か「AIの指摘」を選んでください。"},
                new Object[] {null, "決まりを入力してください。"});
        for (Object[] c : cases) {
            WritingRuleController.RuleRequest request = (WritingRuleController.RuleRequest) c[0];
            assertFailure(failureOf(controller, () -> controller.add(request)), 400, "RULE_INVALID", (String) c[1]);
        }
        assertEquals(List.of(), calls, "入力が誤っているときはDBに触れない");
    }

    @Test
    void 編集は対象と決まりときっかけを直す_その番号がなければ404() {
        WritingRuleController controller = controller();
        Map<String, Object> ok = controller.update(7, new WritingRuleController.RuleRequest("SNS投稿", " 一文を短くする ", "読みにくいと言われた", "ignored"));
        assertEquals("直しました", ok.get("status"));
        assertEquals(WritingRuleRepository.UPDATE_SQL, calls.get(0).sql());
        assertEquals(List.of("SNS投稿", "一文を短くする", "読みにくいと言われた", 7L), calls.get(0).values());

        updatedRows = 0;
        assertFailure(failureOf(controller, () -> controller.update(99, new WritingRuleController.RuleRequest("共通", "短く", "", "本人"))),
                404, "RULE_NOT_FOUND", "その決まりは見つかりませんでした。");
    }

    @Test
    void 無効にしても消さず_有効に戻せる_指定がなければ400() {
        WritingRuleController controller = controller();
        assertEquals("無効にしました", controller.setActive(7, new WritingRuleController.ActiveRequest(false)).get("status"));
        assertEquals("有効にしました", controller.setActive(7, new WritingRuleController.ActiveRequest(true)).get("status"));
        assertEquals(WritingRuleRepository.ACTIVE_SQL, calls.get(0).sql());
        assertEquals(List.of(false, 7L), calls.get(0).values());
        assertEquals(List.of(true, 7L), calls.get(1).values());
        assertTrue(calls.stream().noneMatch(call -> call.sql().contains("DELETE")), "決まりは消さない");

        int before = calls.size();
        assertFailure(failureOf(controller, () -> controller.setActive(7, new WritingRuleController.ActiveRequest(null))),
                400, "RULE_INVALID", "有効にするか無効にするかを指定してください。");
        assertEquals(before, calls.size());

        updatedRows = 0;
        assertFailure(failureOf(controller, () -> controller.setActive(99, new WritingRuleController.ActiveRequest(false))),
                404, "RULE_NOT_FOUND", "その決まりは見つかりませんでした。");
    }

    @Test
    void 削除のAPIはない() {
        assertTrue(Arrays.stream(WritingRuleController.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(DeleteMapping.class)));
    }

    @Test
    void DBにつながらないときは503で返し_接続先や例外の文は出さない() {
        down = true;
        WritingRuleController controller = controller();
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            List<Supplier<Object>> actions = List.of(
                    controller::list,
                    () -> controller.add(new WritingRuleController.RuleRequest("共通", "短く", "", "本人")),
                    () -> controller.update(7, new WritingRuleController.RuleRequest("共通", "短く", "", "本人")),
                    () -> controller.setActive(7, new WritingRuleController.ActiveRequest(false)));
            for (Supplier<Object> action : actions) {
                Failure failure = failureOf(controller, action);
                assertFailure(failure, 503, "DB_UNAVAILABLE", "書き方の決まりのDBを読み書きできませんでした。");
                assertFalse(failure.body().toString().contains("127.0.0.1"), "接続先を応答に出さない");
            }
        } finally {
            System.setOut(original);
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("書き方の決まりの読み書きに失敗しました：SQLException"), out);
        assertFalse(out.contains("127.0.0.1"), "接続先を標準出力に出さない");
    }

    @Test
    void 追加で番号が返らなければ503() {
        rows = List.of();
        returnedId = -1;
        WritingRuleController controller = controller();
        assertFailure(failureOf(controller, () -> controller.add(new WritingRuleController.RuleRequest("共通", "短く", "", "本人"))),
                503, "DB_UNAVAILABLE", "書き方の決まりのDBを読み書きできませんでした。");
    }

    // ===== 道具 =====

    record Failure(int status, MediaType type, Map<?, ?> body) {
    }

    // 呼び出しで投げられた例外を、Spring と同じく受け持ちの @ExceptionHandler で応答に変える。
    private static Failure failureOf(WritingRuleController controller, Supplier<Object> action) {
        RuntimeException thrown;
        try {
            action.get();
            throw new AssertionError("失敗するはずの呼び出しが成功した");
        } catch (RuntimeException e) {
            thrown = e;
        }
        for (Method method : WritingRuleController.class.getDeclaredMethods()) {
            ExceptionHandler handler = method.getAnnotation(ExceptionHandler.class);
            if (handler == null || Arrays.stream(handler.value()).noneMatch(type -> type.isInstance(thrown))) {
                continue;
            }
            try {
                method.setAccessible(true);
                ResponseEntity<?> response = (ResponseEntity<?>) method.invoke(controller, thrown);
                return new Failure(response.getStatusCode().value(), response.getHeaders().getContentType(), (Map<?, ?>) response.getBody());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("受け持つ @ExceptionHandler がない例外：" + thrown.getClass().getName(), thrown);
    }

    private static void assertFailure(Failure failure, int status, String errorCode, String error) {
        assertEquals(status, failure.status());
        assertEquals(MediaType.APPLICATION_JSON, failure.type());
        assertEquals(Map.of("error", error, "errorCode", errorCode), failure.body());
    }

    private Connection open() throws SQLException {
        if (down) {
            throw new SQLException("Connection to 127.0.0.1:1 refused");
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> statement((String) args[0]);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PreparedStatement statement(String sql) {
        Call call = new Call(sql, new ArrayList<>());
        calls.add(call);
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setLong", "setBoolean" -> {
                        call.values().add(args[1]);
                        yield null;
                    }
                    case "executeQuery" -> sql.contains("RETURNING id")
                            ? resultSet(returnedId < 0 ? List.of() : List.<Object[]>of(new Object[] {returnedId}), List.of("id"))
                            : resultSet(rows, List.of("id", "target", "rule", "reason", "source", "active"));
                    case "executeUpdate" -> updatedRows;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet resultSet(List<Object[]> data, List<String> columns) {
        int[] index = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++index[0] < data.size();
                    case "close" -> null;
                    case "getLong", "getString", "getBoolean" -> data.get(index[0])[columns.indexOf((String) args[0])];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
