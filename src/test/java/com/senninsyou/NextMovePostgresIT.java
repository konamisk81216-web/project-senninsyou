package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;

// NextMoveEvidenceReader を、手元の実PostgreSQL（127.0.0.1 の専用テストDB）で確かめる試験。
// （作業時間合計 SUM(work_minutes) は bigint。Long で読み、Integer に収まるときだけ変換する修正を、上限ちょうど・上限＋1で確かめる）
// 実行は明示したときだけ：mvn -Dtest=NextMovePostgresIT test（通常の mvn test の対象には入らない名前にしている）。
// 接続先は SENNINSYOU_PG_IT_URL・_USER・_PASSWORD だけで決め、127.0.0.1 の senninsyou_nextmove_it 以外は拒否する。
// 設定が足りない・違うときは、飛ばさずに失敗にする。本番用の接続設定（DATABASE_URL）には切り替えない。AIは偽物で、OpenAIは呼ばない。
//
// 範囲：tasks 表は、読み取りで使う列だけの簡略版（本番の tasks の定義はリポジトリにない）。opportunities・revenue_records は
// リポジトリの database/001〜003 の定義をそのまま使う。本番スキーマとの一致・永続性・障害復旧は、この試験では確かめていない。
class NextMovePostgresIT {

    private static PostgresItConfig config;
    private static String serverVersion;
    private static final String NO_READ_ROLE = "nextmove_it_noread";
    private static String noReadPassword;
    private final NextMoveEvidenceReader reader = new NextMoveEvidenceReader();

    @BeforeAll
    static void connectOnlyToTheDedicatedLocalDatabase() throws Exception {
        try {
            config = PostgresItConfig.fromEnvironment(System.getenv());
        } catch (PostgresItConfig.Rejected rejected) {
            fail("実PostgreSQLの試験を実行できません：" + rejected.getMessage());
        }
        // URLの形だけでなく、つながった先のサーバーでも、DB名と接続先のアドレスを確かめる。
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT current_database(), host(inet_server_addr()), current_setting('server_version'), current_setting('server_version_num')::int")) {
            assertTrue(result.next());
            assertEquals(PostgresItConfig.DATABASE, result.getString(1), "専用テストDB以外にはつながない");
            assertEquals(PostgresItConfig.HOST, result.getString(2), "127.0.0.1 以外のサーバーにはつながない");
            serverVersion = result.getString(3);
            assertTrue(result.getInt(4) / 10000 == 17, "PostgreSQL 17 で確かめる（実際：" + serverVersion + "）");
        }
        System.out.println("[NextMovePostgresIT] PostgreSQL " + serverVersion + " / 127.0.0.1:" + config.port + "/" + PostgresItConfig.DATABASE);
        noReadPassword = HexFormat.of().formatHex(new SecureRandom().generateSeed(16));
    }

    @AfterAll
    static void dropTheRestrictedRole() throws SQLException {
        if (config == null) return;
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            dropNoReadRole(statement);
        }
    }

    private static Connection open() throws SQLException {
        return open(config.user, config.password, "nextmove-it");
    }

    private static Connection open(String user, String password, String applicationName) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("ApplicationName", applicationName);
        return DriverManager.getConnection(config.url, properties);
    }

    // ---- 表とデータ ----

    @BeforeEach
    void createTables() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            dropNoReadRole(statement);
            statement.execute("DROP TABLE IF EXISTS revenue_records, opportunities, tasks CASCADE");
            // 簡略版の tasks（読み取りで使う列だけ）。
            statement.execute("""
                    CREATE TABLE tasks (
                        id SERIAL PRIMARY KEY,
                        task_name VARCHAR(255) NOT NULL,
                        status VARCHAR(20) NOT NULL,
                        priority VARCHAR(10) NOT NULL,
                        assigned_agent VARCHAR(100)
                    )""");
            for (String file : List.of("001_create_revenue_records.sql", "002_create_opportunities.sql", "003_link_opportunities_to_tasks.sql")) {
                statement.execute(Files.readString(Path.of("database", file), StandardCharsets.UTF_8));
            }
        }
    }

    private static void dropNoReadRole(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + NO_READ_ROLE + "'")) {
            if (!result.next()) return;
        }
        statement.execute("DROP OWNED BY " + NO_READ_ROLE);
        statement.execute("DROP ROLE " + NO_READ_ROLE);
    }

    private static int insertTask(Connection connection, String name, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tasks (task_name, status, priority, assigned_agent) VALUES (?, ?, '高', 'ライターAI') RETURNING id")) {
            statement.setString(1, name);
            statement.setString(2, status);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static long insertOpportunity(Connection connection, String title, Integer taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO opportunities (title, opportunity_type, expected_revenue, estimated_minutes, risk_level, status, notes, task_id) "
                        + "VALUES (?, 'コンテンツ', 3000, 90, '中', '有望', 'メモ', ?) RETURNING id")) {
            statement.setString(1, title);
            if (taskId == null) statement.setNull(2, java.sql.Types.INTEGER); else statement.setInt(2, taskId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long insertRevenue(Connection connection, int taskId, String description, int revenue, int expense, int minutes, LocalDate on)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO revenue_records (task_id, description, revenue, expense, work_minutes, occurred_on) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
            statement.setInt(1, taskId);
            statement.setString(2, description);
            statement.setInt(3, revenue);
            statement.setInt(4, expense);
            statement.setInt(5, minutes);
            statement.setObject(6, on);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private record Seed(long opportunityId, int taskId, List<Long> revenueIds) {
    }

    // 任務1件に結び付いた機会と、収益記録 count 件（11/1 から1日ずつ）。
    private static Seed seed(int count) throws SQLException {
        try (Connection connection = open()) {
            int taskId = insertTask(connection, "記事を1本書く", "進行中");
            long opportunityId = insertOpportunity(connection, "金の成る木", taskId);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(insertRevenue(connection, taskId, "記録" + (i + 1), 1000 * (i + 1), 100 * i, 30 + i, LocalDate.of(2026, 11, 1 + i)));
            }
            return new Seed(opportunityId, taskId, ids);
        }
    }

    // ---- 読み取りの途中に、別の接続で更新を確定する仕掛け（試験の中だけ。本体は変えない） ----

    // 読み取り用の接続を包み、指定したSQLを準備する直前に、別の接続で更新して確定する（それぞれ1回だけ）。
    // weaken が true のときは、比較用に REPEATABLE READ の指定を READ COMMITTED に置き換える（本体の指定を弱めた版の再現）。
    private static final class Hooked {
        final Connection connection;
        final AtomicInteger hooksRun = new AtomicInteger();
        final AtomicReference<Integer> isolationRequested = new AtomicReference<>();
        final AtomicReference<Integer> isolationApplied = new AtomicReference<>();

        Hooked(Connection target, Map<String, SqlAction> beforeSql, boolean weaken) {
            Map<String, SqlAction> pending = new HashMap<>(beforeSql);
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement") && args != null && args.length > 0 && args[0] instanceof String sql) {
                    SqlAction action = pending.remove(sql);
                    if (action != null) {
                        try (Connection other = open()) {
                            other.setAutoCommit(false);
                            action.run(other);
                            other.commit();
                        }
                        hooksRun.incrementAndGet();
                    }
                }
                if (method.getName().equals("setTransactionIsolation") && args != null) {
                    isolationRequested.set((Integer) args[0]);
                    if (weaken) args = new Object[] { Connection.TRANSACTION_READ_COMMITTED };
                    isolationApplied.set((Integer) args[0]);
                }
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class }, handler);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run(Connection connection) throws SQLException;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    // 期待（読み始めた時点の内容）との違いを並べる。読み取りが失敗したら、その理由を1行で返す。
    private static List<String> inconsistencies(NextMoveEvidence expected, ReadOutcome actual) {
        List<String> found = new ArrayList<>();
        if (actual.error != null) {
            found.add("読み取りが失敗：" + actual.error.getMessage());
            return found;
        }
        NextMoveEvidence got = actual.evidence;
        if (!Objects.equals(expected.opportunity(), got.opportunity())) found.add("機会：" + got.opportunity());
        if (!Objects.equals(expected.task(), got.task())) found.add("任務：" + got.task());
        NextMoveEvidence.RevenuePart e = expected.revenue(), g = got.revenue();
        if (e.count() != g.count()) found.add("件数：" + e.count() + " → " + g.count());
        if (!Objects.equals(e.totalRevenue(), g.totalRevenue())) found.add("売上合計：" + e.totalRevenue() + " → " + g.totalRevenue());
        if (!Objects.equals(e.totalExpense(), g.totalExpense())) found.add("経費合計：" + e.totalExpense() + " → " + g.totalExpense());
        if (!Objects.equals(e.totalProfit(), g.totalProfit())) found.add("利益合計：" + e.totalProfit() + " → " + g.totalProfit());
        if (!Objects.equals(e.totalWorkMinutes(), g.totalWorkMinutes())) found.add("作業時間合計：" + e.totalWorkMinutes() + " → " + g.totalWorkMinutes());
        if (!Objects.equals(e.recent(), g.recent())) found.add("明細：" + e.recent().size() + "件 → " + g.recent().size() + "件（内容が違う）");
        return found;
    }

    private record ReadOutcome(NextMoveEvidence evidence, SQLException error) {
    }

    private ReadOutcome readHooked(long opportunityId, Map<String, SqlAction> hooks, boolean weaken, Hooked[] out) throws SQLException {
        try (Connection target = open()) {
            Hooked hooked = new Hooked(target, hooks, weaken);
            out[0] = hooked;
            try {
                return new ReadOutcome(reader.read(hooked.connection, opportunityId), null);
            } catch (SQLException e) {
                return new ReadOutcome(null, e);
            }
        }
    }

    private NextMoveEvidence readFresh(long opportunityId) throws SQLException {
        try (Connection connection = open()) {
            return reader.read(connection, opportunityId);
        }
    }

    // 任務と収益記録の金額を変える更新（任務を読む直前に確定する）。
    private static Map<String, SqlAction> changeTaskAndAmounts(Seed seed, boolean alsoInsertBeforeRecent) {
        Map<String, SqlAction> hooks = new HashMap<>();
        hooks.put(NextMoveEvidenceReader.TASK_SQL, other -> {
            execute(other, "UPDATE tasks SET task_name = '変更後の任務', status = '完了' WHERE id = " + seed.taskId());
            execute(other, "UPDATE revenue_records SET revenue = revenue + 1000 WHERE id = " + seed.revenueIds().get(0));
            execute(other, "UPDATE opportunities SET title = '変更後の機会' WHERE id = " + seed.opportunityId());
        });
        if (alsoInsertBeforeRecent) {
            // 件数と合計を読んだあと、明細を読む直前に、新しい記録を1件足して確定する。
            hooks.put(NextMoveEvidenceReader.REVENUE_RECENT_SQL, other ->
                    insertRevenue(other, seed.taskId(), "割り込みの記録", 5000, 0, 10, LocalDate.of(2026, 11, 30)));
        }
        return hooks;
    }

    // ---- 試験 ----

    @Test
    void readsOneSnapshotWhileAnotherConnectionCommits() throws Exception {
        Seed seed = seed(3);
        NextMoveEvidence before = readFresh(seed.opportunityId());
        Hooked[] hooked = new Hooked[1];

        ReadOutcome outcome = readHooked(seed.opportunityId(), changeTaskAndAmounts(seed, true), false, hooked);

        assertEquals(2, hooked[0].hooksRun.get(), "読み取りの途中で、別の接続の更新を2回確定した");
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, hooked[0].isolationApplied.get());
        assertEquals(List.of(), inconsistencies(before, outcome), "読み始めた時点の内容のまま（機会・任務・件数・合計・明細）");
        assertEquals(3, outcome.evidence().revenue().count());
        assertEquals(new BigDecimal("6000.00"), outcome.evidence().revenue().totalRevenue());

        // 更新は確定していて、新しい読み取りでは見える（試験が空振りしていない）。
        NextMoveEvidence after = readFresh(seed.opportunityId());
        assertEquals("変更後の任務", after.task().name());
        assertEquals("変更後の機会", after.opportunity().title());
        assertEquals(4, after.revenue().count());
        assertEquals(new BigDecimal("12000.00"), after.revenue().totalRevenue());
    }

    @Test
    void recentRecordsStayInTheSameSnapshotWhenMoreThanTheLimit() throws Exception {
        Seed seed = seed(7);
        NextMoveEvidence before = readFresh(seed.opportunityId());
        Hooked[] hooked = new Hooked[1];

        ReadOutcome outcome = readHooked(seed.opportunityId(), changeTaskAndAmounts(seed, true), false, hooked);

        assertEquals(2, hooked[0].hooksRun.get());
        assertEquals(List.of(), inconsistencies(before, outcome));
        assertEquals(7, outcome.evidence().revenue().count());
        assertEquals(NextMoveEvidence.RECENT_RECORD_LIMIT, outcome.evidence().revenue().recent().size());
        assertTrue(outcome.evidence().revenue().recent().stream().noneMatch(r -> r.description().equals("割り込みの記録")),
                "明細の直近5件に、読み始めたあとに確定した記録は入らない");
        assertEquals("割り込みの記録", readFresh(seed.opportunityId()).revenue().recent().get(0).description());
    }

    @Test
    void weakenedIsolationComparisonIsDetectedByTheSameTest() throws Exception {
        // 比較版：REPEATABLE READ を READ COMMITTED に置き換えると、同じ割り込みで食い違いが出て、この試験が見つける。
        Seed seed = seed(3);
        NextMoveEvidence before = readFresh(seed.opportunityId());
        Hooked[] hooked = new Hooked[1];

        ReadOutcome outcome = readHooked(seed.opportunityId(), changeTaskAndAmounts(seed, true), true, hooked);

        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, hooked[0].isolationRequested.get(), "本体は REPEATABLE READ を指定している");
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, hooked[0].isolationApplied.get(), "比較版では READ COMMITTED に弱めた");
        assertEquals(2, hooked[0].hooksRun.get());
        List<String> found = inconsistencies(before, outcome);
        System.out.println("[NextMovePostgresIT] 比較版（件数・合計のあとに記録を追加）で見つかった食い違い：" + found);
        assertFalse(found.isEmpty(), "弱めた版では食い違いを見つける");
        assertNotNull(outcome.error(), "件数（3件）と明細（4件）が合わず、本体の確認で失敗になる");
        assertTrue(outcome.error().getMessage().contains("件数と明細が一致しませんでした"));
    }

    @Test
    void weakenedIsolationComparisonShowsMixedValuesWithoutAnError() throws Exception {
        // 比較版（失敗にならない形）：任務と金額の更新だけ。READ COMMITTED では値が混ざっても例外にならず、値の比較で見つける。
        Seed seed = seed(3);
        NextMoveEvidence before = readFresh(seed.opportunityId());

        Hooked[] strict = new Hooked[1];
        ReadOutcome repeatable = readHooked(seed.opportunityId(), changeTaskAndAmounts(seed, false), false, strict);
        assertEquals(1, strict[0].hooksRun.get());
        assertEquals(List.of(), inconsistencies(before, repeatable), "REPEATABLE READ では混ざらない");

        Seed second = seed(3);
        NextMoveEvidence secondBefore = readFresh(second.opportunityId());
        Hooked[] weak = new Hooked[1];
        ReadOutcome committed = readHooked(second.opportunityId(), changeTaskAndAmounts(second, false), true, weak);
        assertEquals(1, weak[0].hooksRun.get());
        List<String> found = inconsistencies(secondBefore, committed);
        System.out.println("[NextMovePostgresIT] 比較版（任務と金額の更新だけ）で見つかった食い違い：" + found);
        assertNull(committed.error(), "例外にはならない");
        assertTrue(found.stream().anyMatch(line -> line.startsWith("任務：")), "読み始めたあとに確定した任務の名前が混ざる");
        assertTrue(found.stream().anyMatch(line -> line.startsWith("売上合計：")), "読み始めたあとに確定した金額が混ざる");
        assertEquals("金の成る木", committed.evidence().opportunity().title(), "機会は最初に読んだので、変更前のまま（=時点が混ざっている）");
    }

    @Test
    void distinguishesUnrecordedFromRecordedZero() throws Exception {
        long unrecorded, zero, noTask;
        try (Connection connection = open()) {
            int emptyTask = insertTask(connection, "記録のない任務", "未着手");
            unrecorded = insertOpportunity(connection, "記録0件の機会", emptyTask);
            int zeroTask = insertTask(connection, "0円の記録がある任務", "進行中");
            zero = insertOpportunity(connection, "合計0円の機会", zeroTask);
            insertRevenue(connection, zeroTask, "0円の記録", 0, 0, 0, LocalDate.of(2026, 11, 5));
            noTask = insertOpportunity(connection, "任務未登録の機会", null);
        }

        NextMoveEvidence.RevenuePart none = readFresh(unrecorded).revenue();
        assertEquals(0, none.count());
        assertNull(none.totalRevenue(), "記録0件の合計は null（0円にしない）");
        assertNull(none.totalExpense());
        assertNull(none.totalProfit());
        assertNull(none.totalWorkMinutes());
        assertEquals(List.of(), none.recent());

        NextMoveEvidence.RevenuePart zeroPart = readFresh(zero).revenue();
        assertEquals(1, zeroPart.count());
        assertEquals(0, zeroPart.totalRevenue().compareTo(BigDecimal.ZERO), "記録があって合計0円なら0円");
        assertEquals(0, zeroPart.totalExpense().compareTo(BigDecimal.ZERO));
        assertEquals(0, zeroPart.totalProfit().compareTo(BigDecimal.ZERO));
        assertEquals(0, zeroPart.totalWorkMinutes());
        assertEquals(1, zeroPart.recent().size());

        NextMoveEvidence withoutTask = readFresh(noTask);
        assertNull(withoutTask.task(), "任務未登録");
        assertNull(withoutTask.revenue(), "任務未登録なら収益記録は読まない");
        assertNull(readFresh(999_999L), "存在しない機会は null");
    }

    // 作業時間（分）の記録を足した任務と機会。
    private static long seedWorkMinutes(int... minutes) throws SQLException {
        try (Connection connection = open()) {
            int taskId = insertTask(connection, "作業時間の境界の任務", "進行中");
            long opportunityId = insertOpportunity(connection, "作業時間の境界の機会", taskId);
            for (int i = 0; i < minutes.length; i++) {
                insertRevenue(connection, taskId, "境界の記録" + (i + 1), 0, 0, minutes[i], LocalDate.of(2026, 11, 1 + i));
            }
            return opportunityId;
        }
    }

    // DBの側の SUM(work_minutes) の値（bigint であることも確かめる）。
    private static long sumInDatabase(long opportunityId) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT SUM(r.work_minutes), pg_typeof(SUM(r.work_minutes))::text FROM revenue_records r JOIN opportunities o ON o.task_id = r.task_id WHERE o.id = ?")) {
            statement.setLong(1, opportunityId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("bigint", result.getString(2), "PostgreSQL の SUM(integer) は bigint");
                return result.getLong(1);
            }
        }
    }

    @Test
    void totalWorkMinutesAtTheIntegerLimitIsReadExactly() throws Exception {
        long opportunityId = seedWorkMinutes(Integer.MAX_VALUE);
        assertEquals(2_147_483_647L, sumInDatabase(opportunityId));

        NextMoveEvidence.RevenuePart revenue = readFresh(opportunityId).revenue();

        assertEquals(1, revenue.count());
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), revenue.totalWorkMinutes(), "上限ちょうどは正しい値のまま");
        assertEquals(Integer.MAX_VALUE, revenue.recent().get(0).workMinutes());
    }

    @Test
    void totalWorkMinutesOverTheIntegerLimitIsAReadFailureAndTheAiIsNotCalled() throws Exception {
        long opportunityId = seedWorkMinutes(Integer.MAX_VALUE, 1);
        assertEquals(2_147_483_648L, sumInDatabase(opportunityId), "DBの合計は上限＋1");

        // 読み取り：切り捨てず、取得失敗にする。取り消し（rollback）のあと、トランザクションは残らない。
        try (Connection connection = open()) {
            SQLException error = null;
            try {
                reader.read(connection, opportunityId);
            } catch (SQLException e) {
                error = e;
            }
            assertNotNull(error, "上限＋1は取得失敗");
            assertEquals("作業時間合計がIntegerの範囲を超えています。", error.getMessage());
            assertTrue(error.getCause() instanceof ArithmeticException, "Math.toIntExact が範囲の超過を見つけた");
            assertEquals(TransactionState.IDLE, connection.unwrap(BaseConnection.class).getTransactionState());
        }

        // NextMoveService：取得失敗として扱い、AIを呼ばず、実行記録も始めない。接続も閉じる。
        CountingAi ai = new CountingAi();
        CountingLog log = new CountingLog();
        List<Connection> opened = new ArrayList<>();
        NextMoveService service = new NextMoveService(() -> {
            Connection connection = open();
            opened.add(connection);
            return connection;
        }, reader, log, ai);

        NextMoveService.Result result = service.propose(opportunityId);

        assertEquals(NextMoveService.Status.READ_FAILED, result.status());
        assertNull(result.facts());
        assertNull(result.proposal());
        assertEquals(0, ai.asks.get(), "AIの呼び出しは0回");
        assertEquals(0, ai.availableChecks.get());
        assertEquals(0, log.starts.get(), "実行記録も始めない");
        assertEquals(1, opened.size());
        assertTrue(opened.get(0).isClosed(), "接続は閉じている");
    }

    // 収益記録だけ読めない試験用のユーザー（本物のDBの権限エラーで、読み取りを途中で失敗させる）。
    private static void createNoReadRole() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + NO_READ_ROLE + " LOGIN PASSWORD '" + noReadPassword + "'");
            statement.execute("GRANT CONNECT ON DATABASE " + PostgresItConfig.DATABASE + " TO " + NO_READ_ROLE);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + NO_READ_ROLE);
            statement.execute("GRANT SELECT ON opportunities, tasks TO " + NO_READ_ROLE);
        }
    }

    private static List<String> sessionStates(String user) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("SELECT coalesce(state, '') FROM pg_stat_activity WHERE usename = ?")) {
            statement.setString(1, user);
            List<String> states = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) states.add(result.getString(1));
            }
            return states;
        }
    }

    private static List<String> waitUntilNoSession(String user) throws Exception {
        List<String> states = sessionStates(user);
        for (int i = 0; i < 40 && !states.isEmpty(); i++) {
            Thread.sleep(50);
            states = sessionStates(user);
        }
        return states;
    }

    @Test
    void failedReadRollsBackAndLeavesNoOpenTransaction() throws Exception {
        Seed seed = seed(2);
        createNoReadRole();
        try (Connection connection = open(NO_READ_ROLE, noReadPassword, "nextmove-it-noread")) {
            SQLException error = null;
            try {
                reader.read(connection, seed.opportunityId());
            } catch (SQLException e) {
                error = e;
            }
            assertNotNull(error, "収益記録を読む権限がないので、読み取りは失敗する");
            assertEquals("42501", error.getSQLState(), "本物のDBの権限エラー（permission denied）");
            // 取り消し（rollback）のあと、トランザクションは残っていない（接続の側とサーバーの側の両方で確かめる）。
            assertEquals(TransactionState.IDLE, connection.unwrap(BaseConnection.class).getTransactionState());
            assertEquals(List.of("idle"), sessionStates(NO_READ_ROLE), "サーバーの側でも idle（idle in transaction ではない）");
            assertTrue(connection.unwrap(PGConnection.class).getBackendPID() > 0);
        }
        assertEquals(List.of(), waitUntilNoSession(NO_READ_ROLE), "接続を閉じたら、サーバーの側の接続も残らない");
    }

    // ---- NextMoveService（偽のAI・偽の実行記録）と組み合わせる ----

    private static final class CountingAi implements NextMoveAi {
        final AtomicInteger asks = new AtomicInteger();
        final AtomicInteger availableChecks = new AtomicInteger();
        final List<Boolean> connectionClosedAtAsk = new ArrayList<>();
        Connection watched;

        @Override
        public boolean available() {
            availableChecks.incrementAndGet();
            return true;
        }

        @Override
        public String ask(String prompt) {
            asks.incrementAndGet();
            try {
                connectionClosedAtAsk.add(watched != null && watched.isClosed());
            } catch (SQLException e) {
                connectionClosedAtAsk.add(false);
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("opportunity:\\d+").matcher(prompt);
            String evidenceId = matcher.find() ? matcher.group() : "opportunity:0";
            return "{\"nextMove\":\"小さく試す\",\"reasons\":[{\"text\":\"記録を確かめる\",\"evidenceIds\":[\"" + evidenceId + "\"]}],\"missingInfo\":\"読者の反応\"}";
        }
    }

    private static final class CountingLog implements NextMoveService.ActivityLog {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger finishes = new AtomicInteger();

        @Override
        public long start(String agent, String action) {
            starts.incrementAndGet();
            return 501;
        }

        @Override
        public AiActivityRepository.FinishResult finish(long id, String status, String detail) {
            finishes.incrementAndGet();
            return AiActivityRepository.FinishResult.SAVED;
        }
    }

    @Test
    void serviceDoesNotCallAiOrStartARecordWhenTheRealReadFails() throws Exception {
        Seed seed = seed(2);
        createNoReadRole();
        CountingAi ai = new CountingAi();
        CountingLog log = new CountingLog();
        List<Connection> opened = new ArrayList<>();
        NextMoveService service = new NextMoveService(() -> {
            Connection connection = open(NO_READ_ROLE, noReadPassword, "nextmove-it-noread");
            opened.add(connection);
            return connection;
        }, reader, log, ai);

        NextMoveService.Result result = service.propose(seed.opportunityId());

        assertEquals(NextMoveService.Status.READ_FAILED, result.status());
        assertNull(result.facts());
        assertNull(result.proposal());
        assertEquals(0, ai.asks.get(), "AIの呼び出しは0回");
        assertEquals(0, ai.availableChecks.get(), "AIの設定の確認にも進まない");
        assertEquals(0, log.starts.get(), "実行記録も始めない");
        assertEquals(1, opened.size());
        assertTrue(opened.get(0).isClosed(), "接続は閉じている");
        assertEquals(List.of(), waitUntilNoSession(NO_READ_ROLE), "サーバーの側の接続も残らない");
    }

    @Test
    void serviceClosesTheConnectionBeforeCallingTheFakeAi() throws Exception {
        Seed seed = seed(2);
        CountingAi ai = new CountingAi();
        CountingLog log = new CountingLog();
        NextMoveService service = new NextMoveService(() -> {
            Connection connection = open();
            ai.watched = connection;
            return connection;
        }, reader, log, ai);

        NextMoveService.Result result = service.propose(seed.opportunityId());

        assertEquals(NextMoveService.Status.OK, result.status(), result.message());
        assertEquals(1, ai.asks.get(), "偽のAIを1回だけ呼ぶ");
        assertEquals(List.of(true), ai.connectionClosedAtAsk, "AIを呼ぶ時点で、DBの接続は閉じている");
        assertEquals(1, log.starts.get());
        assertEquals(1, log.finishes.get());
        assertNotEquals(null, result.facts());
    }
}
