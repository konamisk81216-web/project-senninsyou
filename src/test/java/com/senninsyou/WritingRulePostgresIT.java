package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 書き方の決まり（WritingRuleRepository）を、手元の実PostgreSQL（127.0.0.1 の専用テストDB）で確かめる試験。
// 実行は明示したときだけ：mvn -Dtest=WritingRulePostgresIT test（通常の mvn test の対象には入らない名前にしている）。
// 接続先は SENNINSYOU_PG_IT_URL・_USER・_PASSWORD だけで決め、127.0.0.1 の senninsyou_nextmove_it 以外は拒否する。
// 設定が足りない・違うときは、飛ばさずに失敗にする。本番用の接続設定（DATABASE_URL）には切り替えない。
// 範囲：writing_rules 表は、リポジトリの database/013 の定義をそのまま使う。本番DBへの適用は、この試験では確かめていない。
class WritingRulePostgresIT {

    private static PostgresItConfig config;

    @BeforeAll
    static void connectOnlyToTheDedicatedLocalDatabase() throws Exception {
        try {
            config = PostgresItConfig.fromEnvironment(System.getenv());
        } catch (PostgresItConfig.Rejected rejected) {
            fail("実PostgreSQLの試験を実行できません：" + rejected.getMessage());
        }
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT current_database(), host(inet_server_addr()), current_setting('server_version')")) {
            assertTrue(result.next());
            assertEquals(PostgresItConfig.DATABASE, result.getString(1), "専用テストDB以外にはつながない");
            assertEquals(PostgresItConfig.HOST, result.getString(2), "127.0.0.1 以外のサーバーにはつながない");
            System.out.println("[WritingRulePostgresIT] PostgreSQL " + result.getString(3) + " / 127.0.0.1:" + config.port + "/" + PostgresItConfig.DATABASE);
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(config.url, config.user, config.password);
    }

    private final WritingRuleRepository repository = new WritingRuleRepository(WritingRulePostgresIT::open);

    @BeforeEach
    void freshTableFromTheMigration() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS writing_rules CASCADE");
            statement.execute(Files.readString(Path.of("database", "013_create_writing_rules.sql"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void 追加すると番号が返り_既定で有効_一覧は有効を先に新しい順() {
        long a = repository.add("共通", "古い決まり", "", "本人");
        long b = repository.add("SNS投稿", "数字と日付を混ぜない", "AIが取り違えた", "AIの指摘");
        long c = repository.add("note記事", "日数は記録で確かめる", "", "本人");
        assertTrue(a > 0 && b > a && c > b, "番号は増えていく");
        assertTrue(repository.setActive(a, false));

        List<WritingRuleRepository.Rule> rules = repository.list();
        assertEquals(List.of(c, b, a), rules.stream().map(WritingRuleRepository.Rule::id).toList(), "有効を先に、新しい順");
        assertEquals(new WritingRuleRepository.Rule(b, "SNS投稿", "数字と日付を混ぜない", "AIが取り違えた", "AIの指摘", true), rules.get(1));
        assertFalse(rules.get(2).active());
    }

    @Test
    void 編集と有効無効の切り替えは1行だけ変え_更新日時を進める_ない番号はfalse() throws Exception {
        long id = repository.add("共通", "短く書く", "", "本人");
        long other = repository.add("共通", "変えない決まり", "", "本人");
        Thread.sleep(20);
        assertTrue(repository.update(id, "note記事", "一文を短く書く", "読みにくいと言われた"));
        assertTrue(repository.setActive(id, false));
        assertTrue(repository.setActive(id, true));
        assertFalse(repository.update(999_999, "共通", "x", ""), "ない番号は false");
        assertFalse(repository.setActive(999_999, false), "ない番号は false");

        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT id, target, rule, reason, source, active, updated_at > created_at AS touched FROM writing_rules ORDER BY id")) {
            assertTrue(result.next());
            assertEquals(id, result.getLong("id"));
            assertEquals("note記事", result.getString("target"));
            assertEquals("一文を短く書く", result.getString("rule"));
            assertEquals("読みにくいと言われた", result.getString("reason"));
            assertEquals("本人", result.getString("source"), "きっかけの種類は編集で変わらない");
            assertTrue(result.getBoolean("active"));
            assertTrue(result.getBoolean("touched"), "更新日時が進む");
            assertTrue(result.next());
            assertEquals(other, result.getLong("id"));
            assertEquals("変えない決まり", result.getString("rule"));
            assertFalse(result.getBoolean("touched"), "ほかの行は変えない");
            assertFalse(result.next());
        }
    }

    @Test
    void 表の制約で_知らない対象や種類は保存されない() throws Exception {
        assertThrows(WritingRuleRepository.Unavailable.class, () -> repository.add("ブログ", "x", "", "本人"));
        assertThrows(WritingRuleRepository.Unavailable.class, () -> repository.add("共通", "x", "", "Gemini"));
        long id = repository.add("共通", "x", "", "本人");
        assertThrows(WritingRuleRepository.Unavailable.class, () -> repository.update(id, "ブログ", "x", ""));
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*), min(target) FROM writing_rules")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1), "制約に反した行は残らない");
            assertEquals("共通", result.getString(2), "失敗した編集で対象は変わらない");
        }
    }

    @Test
    void 定義は2回流しても失敗しない() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(Path.of("database", "013_create_writing_rules.sql"), StandardCharsets.UTF_8));
        }
        assertEquals(0, repository.list().size());
    }
}
