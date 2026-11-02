package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 修正版の保存が、作成本数・平均作成時間（実績の集計）を歪めないことの試験。
// 本物のPostgreSQLは使わない。代わりに、本物のSQLの文面（INSERT の列と値、集計の WHERE の条件）を読んで動く
// 小さな表の模型（writer_jobs）を使う。SQLの文法やPostgreSQL固有の動き（既定値・型・ロック）は確かめられない。実行: mvn test
class WriterJobMetricsTest {

    // 変更前の集計（修正版の印がない）。同じ模型で、印がないと歪むことを確かめるために使う。
    private static final String SUMMARY_BEFORE_012 = """
            SELECT
                (SELECT COUNT(*) FROM writer_jobs WHERE status = '完了') AS article_count,
                (SELECT COALESCE(ROUND(AVG(EXTRACT(EPOCH FROM (updated_at - created_at)))), 0)
                   FROM writer_jobs WHERE status = '完了') AS avg_seconds,
                (SELECT COUNT(*) FROM post_drafts WHERE status = '承認済み') AS approved_posts
            """;

    private final WriterJobsTable table = new WriterJobsTable();
    private AIController controller;

    @BeforeEach
    void setUp() throws Exception {
        // AIが書いた完了の下書き2本（作成に60秒と120秒）。印の列は既定値（FALSE）のまま。
        table.addAiDraft("AIの下書き1", 60);
        table.addAiDraft("AIの下書き2", 120);

        controller = (AIController) unsafe().allocateInstance(AIController.class);
        Connection connection = table.connection();
        set(controller, "writerJobRepository", new WriterJobRepository("", "", "") {
            @Override public long saveEditedDraft(Map<String, String> draft) { return saveEditedDraft(connection, draft); }
        });
    }

    @Test
    void 修正版を何回保存しても_作成本数と平均作成時間は変わらない() {
        assertEquals(Map.of("articleCount", "2", "averageWritingSeconds", "90", "approvedPosts", "0"), summary());

        for (int i = 1; i <= 3; i++) {
            Map<String, String> result = controller.saveEditedWriterJob(edited("直したタイトル" + i));
            assertEquals("完了", result.get("status"));
            assertEquals(Map.of("articleCount", "2", "averageWritingSeconds", "90", "approvedPosts", "0"), summary(),
                    i + "回目の保存のあとも、作成本数2本・平均90秒のまま");
        }
        assertEquals(5, table.rows.size(), "修正版は3件増えている（元の下書きは残っている）");
    }

    @Test
    void 採点の対象になる直近の下書きは_最後に保存した修正版() {
        controller.saveEditedWriterJob(edited("1回目に直したタイトル"));
        controller.saveEditedWriterJob(edited("2回目に直したタイトル"));
        Map<String, Object> latest = table.latestById();
        assertEquals("2回目に直したタイトル", latest.get("title"), "直近の下書き（id の大きい順の先頭）は最後の修正版");
        assertEquals(Boolean.TRUE, latest.get("is_edited"));
        assertEquals("完了", latest.get("status"));
        assertEquals("AIの下書き1", table.rows.get(0).get("title"), "元の下書きは書き換えない");
        assertEquals("AIの下書き2", table.rows.get(1).get("title"));
    }

    @Test
    void 変更前の集計では_修正版で作成本数が増え平均作成時間が下がる() {
        for (int i = 1; i <= 3; i++) controller.saveEditedWriterJob(edited("直したタイトル" + i));
        assertEquals(Map.of("article_count", 5L, "avg_seconds", 36L), table.summary(SUMMARY_BEFORE_012),
                "印のない集計では2本→5本、90秒→36秒に歪む（この試験の模型が歪みを見分けられることの確認）");
    }

    @Test
    void 保存のSQLは修正版の印を立て_集計のSQLは両方の集計で印を除く() {
        controller.saveEditedWriterJob(edited("直したタイトル"));
        String insert = table.executed.stream().filter(sql -> sql.startsWith("INSERT")).findFirst().orElseThrow();
        assertTrue(insert.contains("(status, is_edited, theme,") && insert.contains("VALUES ('完了', TRUE, ?"), insert);
        assertTrue(!insert.contains("UPDATE"), "既存の行は書き換えない");

        summary();
        String select = table.executed.get(table.executed.size() - 1);
        assertEquals(2, count(select, "FROM writer_jobs WHERE status = '完了' AND is_edited = FALSE"),
                "作成本数と平均作成時間の両方で修正版を除く: " + select);
    }

    private Map<String, String> summary() {
        return new MetricsRepository("", "", "").getSummary(table.connection());
    }

    private static AIController.EditedWriterRequest edited(String title) {
        return new AIController.EditedWriterRequest(
                "テーマ", "読者", "500円", "材料", "", title, "無料", "有料", "説明", "", "");
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) found++;
        return found;
    }

    // ===== 表の模型 =====
    // INSERT：列と値の並びを本物のSQLから読み、書かれていない列は表の既定値（is_edited は FALSE、時刻は今）にする。
    // 集計：本物のSQLの writer_jobs の集計それぞれについて、WHERE の「列 = 値」を AND でつないだ条件で数える。
    static final class WriterJobsTable {
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<String> executed = new ArrayList<>();
        private long nextId = 1;
        private long clockSeconds = 1_000_000;

        void addAiDraft(String title, long writingSeconds) {
            Map<String, Object> row = defaults();
            row.put("status", "完了");
            row.put("title", title);
            row.put("updated_at", (long) row.get("created_at") + writingSeconds);
            rows.add(row);
        }

        Map<String, Object> latestById() {
            return rows.stream().max((a, b) -> Long.compare((long) a.get("id"), (long) b.get("id"))).orElseThrow();
        }

        private Map<String, Object> defaults() {
            clockSeconds += 1000;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", nextId++);
            row.put("status", "受付");
            row.put("is_edited", Boolean.FALSE);
            row.put("created_at", clockSeconds);
            row.put("updated_at", clockSeconds);
            return row;
        }

        long insert(String sql, Map<Integer, String> params) {
            Matcher m = Pattern.compile("INSERT INTO writer_jobs \\((.*?)\\) VALUES \\((.*?)\\) RETURNING id").matcher(sql);
            if (!m.find()) throw new IllegalStateException("読めないINSERT: " + sql);
            String[] columns = m.group(1).split(",\\s*");
            String[] values = m.group(2).split(",\\s*");
            Map<String, Object> row = defaults();
            int param = 1;
            for (int i = 0; i < columns.length; i++) {
                String value = values[i];
                row.put(columns[i], value.equals("?") ? params.get(param++) : literal(value));
            }
            rows.add(row);
            return (long) row.get("id");
        }

        Map<String, Long> summary(String sql) {
            String flat = sql.replaceAll("\\s+", " ");
            Matcher m = Pattern.compile("\\(SELECT (COUNT\\(\\*\\)|COALESCE\\(ROUND\\(AVG\\(EXTRACT\\(EPOCH FROM \\(updated_at - created_at\\)\\)\\)\\), 0\\)) "
                    + "FROM writer_jobs WHERE (.*?)\\) AS (\\w+)").matcher(flat);
            Map<String, Long> result = new LinkedHashMap<>();
            while (m.find()) {
                List<Map<String, Object>> matched = rows.stream().filter(row -> matches(row, m.group(2))).toList();
                long value = m.group(1).startsWith("COUNT")
                        ? matched.size()
                        : Math.round(matched.stream().mapToLong(row -> (long) row.get("updated_at") - (long) row.get("created_at")).average().orElse(0));
                result.put(m.group(3), value);
            }
            return result;
        }

        private static boolean matches(Map<String, Object> row, String where) {
            for (String condition : where.split(" AND ")) {
                String[] parts = condition.split(" = ");
                if (!literal(parts[1].trim()).equals(row.get(parts[0].trim()))) return false;
            }
            return true;
        }

        private static Object literal(String value) {
            if (value.equals("TRUE")) return Boolean.TRUE;
            if (value.equals("FALSE")) return Boolean.FALSE;
            if (value.startsWith("'") && value.endsWith("'")) return value.substring(1, value.length() - 1);
            throw new IllegalStateException("読めない値: " + value);
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> statement(((String) args[0]).replaceAll("\\s+", " ").trim());
                        case "close" -> null;
                        case "isClosed" -> false;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private PreparedStatement statement(String sql) {
            executed.add(sql);
            Map<Integer, String> params = new LinkedHashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setString" -> { params.put((Integer) args[0], (String) args[1]); yield null; }
                        case "executeQuery" -> sql.startsWith("INSERT")
                                ? resultSet(Map.of("id", insert(sql, params)))
                                : resultSet(withPosts(summary(sql)));
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private static Map<String, Object> withPosts(Map<String, Long> summary) {
            Map<String, Object> row = new LinkedHashMap<>(summary);
            row.put("approved_posts", 0L);
            return row;
        }

        private static ResultSet resultSet(Map<String, Object> row) {
            boolean[] read = {false};
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> { boolean first = !read[0]; read[0] = true; yield first; }
                        case "getLong" -> ((Number) row.get((String) args[0])).longValue();
                        case "getInt" -> ((Number) row.get((String) args[0])).intValue();
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
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
