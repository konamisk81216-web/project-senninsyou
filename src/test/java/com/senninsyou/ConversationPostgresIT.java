package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;

import com.senninsyou.ConversationRepository.ExchangeSave;

// 相談の1往復の一括保存（ConversationRepository.addExchange）を、手元の実PostgreSQL（127.0.0.1 の専用テストDB）で確かめる試験。
// 実行は明示したときだけ：mvn -Dtest=ConversationPostgresIT test（通常の mvn test の対象には入らない名前にしている）。
// 接続先は SENNINSYOU_PG_IT_URL・_USER・_PASSWORD だけで決め、127.0.0.1 の senninsyou_nextmove_it 以外は拒否する。
// 設定が足りない・違うときは、飛ばさずに失敗にする。本番用の接続設定（DATABASE_URL）には切り替えない。AIは偽物で、OpenAIは呼ばない。
//
// 範囲：conversations 表は、リポジトリの database/010 の定義をそのまま使う。2件目の INSERT を失敗させるトリガーは、この試験の中だけで
// 作る試験用のもの（本番のスキーマにはない）。本番スキーマとの一致・永続性・障害復旧は、この試験では確かめていない。
class ConversationPostgresIT {

    private static final String APP_NAME = "conversation-it";
    private static PostgresItConfig config;
    private static String serverVersion;

    @BeforeAll
    static void connectOnlyToTheDedicatedLocalDatabase() throws Exception {
        try {
            config = PostgresItConfig.fromEnvironment(System.getenv());
        } catch (PostgresItConfig.Rejected rejected) {
            fail("実PostgreSQLの試験を実行できません：" + rejected.getMessage());
        }
        // URLの形だけでなく、つながった先のサーバーでも、DB名と接続先のアドレスを確かめる。
        try (Connection connection = open("conversation-it-check");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT current_database(), host(inet_server_addr()), current_setting('server_version'), current_setting('server_version_num')::int")) {
            assertTrue(result.next());
            assertEquals(PostgresItConfig.DATABASE, result.getString(1), "専用テストDB以外にはつながない");
            assertEquals(PostgresItConfig.HOST, result.getString(2), "127.0.0.1 以外のサーバーにはつながない");
            serverVersion = result.getString(3);
            assertTrue(result.getInt(4) / 10000 == 17, "PostgreSQL 17 で確かめる（実際：" + serverVersion + "）");
        }
        System.out.println("[ConversationPostgresIT] PostgreSQL " + serverVersion + " / 127.0.0.1:" + config.port + "/" + PostgresItConfig.DATABASE);
    }

    private static Connection open(String applicationName) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.user);
        properties.setProperty("password", config.password);
        properties.setProperty("ApplicationName", applicationName);
        return DriverManager.getConnection(config.url, properties);
    }

    // 試験する保存の部品が使う接続。別の接続（確認用）と区別できるよう、ApplicationName を分ける。
    private static Connection openForRepository() throws SQLException {
        return open(APP_NAME);
    }

    @BeforeEach
    void createTable() throws Exception {
        try (Connection connection = open("conversation-it-setup"); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS conversations CASCADE");
            statement.execute("DROP FUNCTION IF EXISTS conversation_it_fail_ai_row()");
            statement.execute("DROP FUNCTION IF EXISTS conversation_it_skip_ai_row()");
            statement.execute(Files.readString(Path.of("database", "010_create_conversations.sql"), StandardCharsets.UTF_8));
        }
    }

    // 試験用：AI将軍の行（2件目）の INSERT を、サーバー側で失敗させる。本番のスキーマにはない。
    private static void failTheAiRowOnTheServer() throws SQLException {
        try (Connection connection = open("conversation-it-setup"); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION conversation_it_fail_ai_row() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'conversation-it: AI row rejected for the test';
                    END $$""");
            statement.execute("""
                    CREATE TRIGGER conversation_it_fail_ai_row BEFORE INSERT ON conversations
                    FOR EACH ROW WHEN (NEW.role = 'AI将軍') EXECUTE FUNCTION conversation_it_fail_ai_row()""");
        }
    }

    // 試験用：AI将軍の行（2件目）の INSERT を、エラーにせず書かずに飛ばす（更新件数0になる）。本番のスキーマにはない。
    private static void skipTheAiRowOnTheServer() throws SQLException {
        try (Connection connection = open("conversation-it-setup"); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION conversation_it_skip_ai_row() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RETURN NULL;
                    END $$""");
            statement.execute("""
                    CREATE TRIGGER conversation_it_skip_ai_row BEFORE INSERT ON conversations
                    FOR EACH ROW WHEN (NEW.role = 'AI将軍') EXECUTE FUNCTION conversation_it_skip_ai_row()""");
        }
    }

    private record Row(String role, String message, String xmin) {
    }

    private static List<Row> rows() throws SQLException {
        try (Connection connection = open("conversation-it-check");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT role, message, xmin::text FROM conversations ORDER BY id")) {
            List<Row> rows = new ArrayList<>();
            while (result.next()) rows.add(new Row(result.getString(1), result.getString(2), result.getString(3)));
            return rows;
        }
    }

    private static long countFromAnotherConnection() throws SQLException {
        try (Connection connection = open("conversation-it-check");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM conversations")) {
            result.next();
            return result.getLong(1);
        }
    }

    // 保存の部品の接続が、トランザクションを開いたまま残っていないこと（閉じられて、セッションがなくなること）。
    private static List<String> remainingSessions() throws Exception {
        List<String> states = sessionStates();
        for (int i = 0; i < 40 && !states.isEmpty(); i++) {
            Thread.sleep(50);
            states = sessionStates();
        }
        return states;
    }

    private static List<String> sessionStates() throws SQLException {
        try (Connection connection = open("conversation-it-check");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT coalesce(state, '') FROM pg_stat_activity WHERE application_name = ?")) {
            statement.setString(1, APP_NAME);
            List<String> states = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) states.add(result.getString(1));
            }
            return states;
        }
    }

    // ---- 本物の接続を包み、INSERT の前後と確定の前に、決めた処理を差し込む ----

    interface Hook {
        void run() throws Exception;
    }

    private static final class Hooked {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        Hook afterFirstInsert = () -> { };
        Hook beforeCommit = () -> { };
        // 確定の応答が届かない状況の再現：true なら本当に確定してから例外、false なら確定を送らずに例外。
        Boolean commitThenLoseTheReply;
        final AtomicInteger inserts = new AtomicInteger();

        Connection wrap(Connection real) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "prepareStatement" -> {
                                return statement((PreparedStatement) invoke(real, method, args));
                            }
                            case "commit" -> {
                                beforeCommit.run();
                                if (commitThenLoseTheReply != null) {
                                    if (commitThenLoseTheReply) {
                                        real.commit();
                                        events.add("commit:sent-and-reply-lost");
                                    } else {
                                        events.add("commit:not-sent");
                                    }
                                    throw new SQLException("試験用：確定の応答を受け取れなかった");
                                }
                                real.commit();
                                events.add("commit");
                                return null;
                            }
                            case "rollback" -> {
                                real.rollback();
                                events.add("rollback");
                                return null;
                            }
                            case "close" -> {
                                real.close();
                                events.add("close");
                                return null;
                            }
                            default -> {
                                return invoke(real, method, args);
                            }
                        }
                    });
        }

        private PreparedStatement statement(PreparedStatement real) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("executeUpdate")) {
                            return invoke(real, method, args);
                        }
                        int number = inserts.incrementAndGet();
                        try {
                            Object updated = invoke(real, method, args);
                            events.add("insert" + number + ":" + updated);
                            if (number == 1) afterFirstInsert.run();
                            return updated;
                        } catch (SQLException e) {
                            events.add("insert" + number + ":failed " + e.getSQLState());
                            throw e;
                        }
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static ConversationRepository repository(Hooked hooked) {
        return new ConversationRepository(() -> hooked.wrap(openForRepository()));
    }

    // ===== 試験 =====

    @Test
    void savesBothRowsInOneTransaction() throws Exception {
        Hooked hooked = new Hooked();
        assertEquals(ExchangeSave.SAVED, repository(hooked).addExchange("相談です", "ハル：価格を決めましょう。"));

        List<Row> rows = rows();
        assertEquals(2, rows.size());
        assertEquals("利用者", rows.get(0).role());
        assertEquals("相談です", rows.get(0).message());
        assertEquals("AI将軍", rows.get(1).role());
        assertEquals("ハル：価格を決めましょう。", rows.get(1).message());
        // 2行を書いたトランザクションの番号（xmin）が同じ＝同じトランザクションで書かれた。
        assertEquals(rows.get(0).xmin(), rows.get(1).xmin(), "2件は同じトランザクション");
        assertEquals(List.of("insert1:1", "insert2:1", "commit", "close"), hooked.events);
        assertEquals(List.of(), remainingSessions());
    }

    @Test
    void anotherConnectionSeesNeitherRowBeforeTheCommit() throws Exception {
        Hooked hooked = new Hooked();
        List<Long> seen = new ArrayList<>();
        hooked.afterFirstInsert = () -> seen.add(countFromAnotherConnection());
        hooked.beforeCommit = () -> seen.add(countFromAnotherConnection());

        assertEquals(ExchangeSave.SAVED, repository(hooked).addExchange("相談です", "ガク：まず計測を入れます。"));

        assertEquals(List.of(0L, 0L), seen, "1件目のあとも、2件目のあと（確定の直前）も、別の接続からは0件（片方だけは見えない）");
        assertEquals(2L, countFromAnotherConnection(), "確定のあとは2件");
    }

    @Test
    void secondInsertFailureRollsBackBothRows() throws Exception {
        failTheAiRowOnTheServer();
        Hooked hooked = new Hooked();
        List<Long> seen = new ArrayList<>();
        hooked.afterFirstInsert = () -> seen.add(countFromAnotherConnection());

        assertEquals(ExchangeSave.NOT_SAVED, repository(hooked).addExchange("相談です", "ハル：価格です。"));

        assertEquals(List.of("insert1:1", "insert2:failed P0001", "rollback", "close"), hooked.events,
                "1件目は書けて、2件目がサーバー側で失敗し、確定せずに取り消した");
        assertEquals(List.of(0L), seen);
        assertEquals(List.of(), rows(), "1件目も残らない");
        assertEquals(List.of(), remainingSessions(), "トランザクションを開いたままの接続は残らない");
    }

    @Test
    void secondInsertThatWritesNoRowRollsBackTheFirstRow() throws Exception {
        skipTheAiRowOnTheServer();
        Hooked hooked = new Hooked();

        assertEquals(ExchangeSave.NOT_SAVED, repository(hooked).addExchange("相談です", "ハル：価格です。"));

        assertEquals(List.of("insert1:1", "insert2:0", "rollback", "close"), hooked.events,
                "2件目はエラーなしで0行。確定せずに取り消した");
        assertEquals(List.of(), rows(), "1件目も残らない");
        assertEquals(List.of(), remainingSessions());
    }

    @Test
    void commitWhoseReplyIsLostIsUnknownEvenThoughTheRowsWereSaved() throws Exception {
        Hooked hooked = new Hooked();
        hooked.commitThenLoseTheReply = true;

        assertEquals(ExchangeSave.UNKNOWN, repository(hooked).addExchange("相談です", "ハル：価格です。"));

        assertEquals(List.of("insert1:1", "insert2:1", "commit:sent-and-reply-lost", "close"), hooked.events,
                "保存し直さない。確定のあとに取り消しも送らない");
        assertEquals(2, rows().size(), "実際には保存されている。だから「保存されなかった」とは扱わない");
    }

    @Test
    void commitThatNeverReachedTheServerIsAlsoUnknownAndLeavesNothing() throws Exception {
        Hooked hooked = new Hooked();
        hooked.commitThenLoseTheReply = false;

        assertEquals(ExchangeSave.UNKNOWN, repository(hooked).addExchange("相談です", "ハル：価格です。"));

        assertEquals(List.of("insert1:1", "insert2:1", "commit:not-sent", "close"), hooked.events);
        assertEquals(List.of(), rows(), "確定が届かなければ、接続を閉じたときにサーバー側で取り消される");
        assertEquals(List.of(), remainingSessions());
    }

    // 比較：今までの保存のしかた（1件ずつ別の接続で自動確定）。同じ試験の条件で、片方だけが見え・片方だけが残ることを示す。
    @Test
    void comparisonSeparateSavesShowAndKeepOnlyTheUserRow() throws Exception {
        failTheAiRowOnTheServer();
        ConversationRepository separate = new ConversationRepository(ConversationPostgresIT::openForRepository);

        separate.add("利用者", "相談です");
        assertEquals(1L, countFromAnotherConnection(), "1件目は書いた時点で別の接続から見える");
        separate.add("AI将軍", "ハル：価格です。");

        List<Row> rows = rows();
        assertEquals(List.of("利用者"), rows.stream().map(Row::role).toList(), "2件目が失敗しても1件目だけが残る");
    }

    // ---- AIController を通した流れ（AIは 127.0.0.1 の偽のサーバー。DBの部品は会話の保存だけ本物） ----

    @Test
    void controllerSavesTheExchangeAfterTheAiAnswerIsChecked() throws Exception {
        try (FakeAi ai = new FakeAi()) {
            Hooked hooked = new Hooked();
            AIController controller = controller(ai, hooked);
            String result = controller.command(new AIController.CommandRequest("相談です", "", "discuss"));

            assertTrue(result.contains("\"speaker\":\"両方\""));
            assertEquals(1, ai.requests.get());
            assertEquals(List.of("ai:answered", "open"), ai.order.subList(0, 2), "AIの返答のあとに保存の接続を開く");
            List<Row> rows = rows();
            assertEquals(List.of("利用者", "AI将軍"), rows.stream().map(Row::role).toList());
            assertEquals("ハル：小さく売ります。\nガク：決済は手作業で始めます。", rows.get(1).message());
            assertEquals(rows.get(0).xmin(), rows.get(1).xmin());
            assertEquals(List.of("start", "finish 完了 "), ai.activities);
        }
    }

    @Test
    void controllerReturnsTheAnswerWhenTheSecondInsertFailsAndDoesNotRetry() throws Exception {
        failTheAiRowOnTheServer();
        try (FakeAi ai = new FakeAi()) {
            Hooked hooked = new Hooked();
            AIController controller = controller(ai, hooked);
            String result = controller.command(new AIController.CommandRequest("相談です", "", "discuss"));

            assertTrue(result.contains("\"haruText\":\"小さく売ります。\""), "生成済みの返答は返る");
            assertEquals(1, ai.requests.get(), "AIは呼び直さない");
            assertEquals(1, ai.opens.get(), "保存はやり直さない");
            assertEquals(List.of("insert1:1", "insert2:failed P0001", "rollback", "close"), hooked.events);
            assertEquals(List.of(), rows());
            assertEquals(List.of("start", "finish 完了 "), ai.activities, "返答形式のエラーにしない");
            assertEquals(List.of(), remainingSessions());
        }
    }

    private static final class FakeAi implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final List<String> activities = Collections.synchronizedList(new ArrayList<>());

        FakeAi() throws Exception {
            String body = "{\"summary\":\"結論です。\",\"revenueInsight\":\"\",\"performanceDecision\":\"\",\"marketOpportunity\":\"\","
                    + "\"nextTask\":\"\",\"priority\":\"\",\"assignedAgent\":\"\",\"action\":\"\",\"actionLabel\":\"\","
                    + "\"actionTheme\":\"\",\"actionAudience\":\"\",\"speaker\":\"両方\",\"speakerReason\":\"目的から判断\","
                    + "\"haruText\":\"小さく売ります。\",\"gakuText\":\"決済は手作業で始めます。\",\"clarifyQuestion\":\"\"}";
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/responses", exchange -> {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                order.add("ai:answered");  // 返答を送る前に記録する（受け取った側が先に進んでも順番が崩れないように）
                byte[] bytes = ("{\"id\":\"resp_test\",\"object\":\"response\",\"created_at\":0,\"status\":\"completed\","
                        + "\"model\":\"gpt-5.2\",\"output\":[{\"type\":\"message\",\"id\":\"msg_test\",\"status\":\"completed\","
                        + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\""
                        + body.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"annotations\":[]}]}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    // 本物の AIController を組み立てる（コンストラクタは DATABASE_URL を読むので通さない）。会話の保存だけ実DBにつなぐ。
    private static AIController controller(FakeAi fake, Hooked hooked) throws Exception {
        AIService ai = new AIService();
        set(ai, "client", OpenAIOkHttpClient.builder().apiKey("test").baseUrl(fake.baseUrl()).maxRetries(0).build());

        AIController created = (AIController) unsafe().allocateInstance(AIController.class);
        set(created, "aiService", ai);
        set(created, "repository", new TaskRepository("", "", "") {
            @Override public String getAllTasksAsText() { return "未完了のタスクはありません。"; }
        });
        set(created, "revenueContextService", new RevenueContextService(null, null) {
            @Override public String buildContext() { return "収益記録はありません。"; }
        });
        set(created, "activityRepository", new AiActivityRepository("", "", "") {
            @Override public long start(String agent, String action) { fake.activities.add("start"); return 1; }
            @Override public void finish(long id, String status, String detail) { fake.activities.add("finish " + status + " " + detail); }
        });
        set(created, "conversationRepository", new ConversationRepository(() -> {
            fake.opens.incrementAndGet();
            fake.order.add("open");
            return hooked.wrap(openForRepository());
        }));
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
