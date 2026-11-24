package com.senninsyou;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// 書き方の決まり（writing_rules）の読み書き。
// 本人のダメ出しやAIの指摘を、次の下書きで守る決まりとして残す。決まりは消さず、使わないときは無効にする。
// 読み書きに失敗したときは、黙って空にせず Unavailable を投げる（画面に「読めなかった」と出すため）。
public class WritingRuleRepository {

    static final List<String> TARGETS = List.of("共通", "note記事", "SNS投稿");
    static final List<String> SOURCES = List.of("本人", "AIの指摘");

    static final String LIST_SQL =
            "SELECT id, target, rule, reason, source, active FROM writing_rules ORDER BY active DESC, id DESC";
    static final String INSERT_SQL =
            "INSERT INTO writing_rules (target, rule, reason, source) VALUES (?, ?, ?, ?) RETURNING id";
    static final String UPDATE_SQL =
            "UPDATE writing_rules SET target = ?, rule = ?, reason = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
    static final String ACTIVE_SQL =
            "UPDATE writing_rules SET active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
    // 下書きのAIへ渡す決まり。有効なもののうち、その対象と「共通」を、古い順に。
    static final String RULES_FOR_TARGET_SQL =
            "SELECT rule FROM writing_rules WHERE active AND target IN ('共通', ?) ORDER BY id";

    interface ConnectionOpener {
        Connection open() throws SQLException;
    }

    record Rule(long id, String target, String rule, String reason, String source, boolean active) {
    }

    // DBが読めない・書けない。原因の文（接続先などが入ることがある）は画面へ渡さない。
    static final class Unavailable extends RuntimeException {
        Unavailable(Throwable cause) {
            super("書き方の決まりのDBを読み書きできませんでした。", cause);
        }
    }

    private final ConnectionOpener connections;

    public WritingRuleRepository(String jdbcUrl, String user, String password) {
        this(() -> DriverManager.getConnection(jdbcUrl, user, password));
    }

    WritingRuleRepository(ConnectionOpener connections) {
        this.connections = connections;
    }

    // 有効な決まりを先に、新しい順に並べる。
    public List<Rule> list() {
        try (
            Connection connection = connections.open();
            PreparedStatement statement = connection.prepareStatement(LIST_SQL);
            ResultSet result = statement.executeQuery()
        ) {
            List<Rule> rules = new ArrayList<>();
            while (result.next()) {
                rules.add(new Rule(
                        result.getLong("id"),
                        result.getString("target"),
                        result.getString("rule"),
                        result.getString("reason"),
                        result.getString("source"),
                        result.getBoolean("active")));
            }
            return rules;
        } catch (SQLException | RuntimeException e) {
            throw unavailable(e);
        }
    }

    // 追加した決まりの番号を返す。
    public long add(String target, String rule, String reason, String source) {
        try (
            Connection connection = connections.open();
            PreparedStatement statement = connection.prepareStatement(INSERT_SQL)
        ) {
            statement.setString(1, target);
            statement.setString(2, rule);
            statement.setString(3, reason);
            statement.setString(4, source);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("追加した決まりの番号が返らなかった");
                }
                return result.getLong("id");
            }
        } catch (SQLException | RuntimeException e) {
            throw unavailable(e);
        }
    }

    // 対象・決まり・きっかけを直す。その番号の決まりがなければ false。
    public boolean update(long id, String target, String rule, String reason) {
        try (
            Connection connection = connections.open();
            PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)
        ) {
            statement.setString(1, target);
            statement.setString(2, rule);
            statement.setString(3, reason);
            statement.setLong(4, id);
            return statement.executeUpdate() == 1;
        } catch (SQLException | RuntimeException e) {
            throw unavailable(e);
        }
    }

    // 有効・無効を切り替える。その番号の決まりがなければ false。
    public boolean setActive(long id, boolean active) {
        try (
            Connection connection = connections.open();
            PreparedStatement statement = connection.prepareStatement(ACTIVE_SQL)
        ) {
            statement.setBoolean(1, active);
            statement.setLong(2, id);
            return statement.executeUpdate() == 1;
        } catch (SQLException | RuntimeException e) {
            throw unavailable(e);
        }
    }

    // 下書きのAIへ渡す形（1行に1つ、「・」で始める）。有効な決まりがなければ空。
    public String activeRulesText(String target) {
        try (
            Connection connection = connections.open();
            PreparedStatement statement = connection.prepareStatement(RULES_FOR_TARGET_SQL)
        ) {
            statement.setString(1, target);
            StringBuilder text = new StringBuilder();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    text.append("・").append(result.getString("rule")).append('\n');
                }
            }
            return text.toString().strip();
        } catch (SQLException | RuntimeException e) {
            throw unavailable(e);
        }
    }

    // 下書きを作るとき用。決まりが読めなくても下書きは止めず、決まりなしで作る（読めなかったことは標準出力に残す）。
    public String activeRulesTextOrEmpty(String target) {
        try {
            return activeRulesText(target);
        } catch (Unavailable e) {
            System.out.println("書き方の決まりを読めなかったため、決まりなしで下書きを作ります（対象：" + target + "）。");
            return "";
        }
    }

    private static Unavailable unavailable(Exception e) {
        if (e instanceof Unavailable already) {
            return already;
        }
        // 例外の種類だけを残す（メッセージには接続先などが入ることがあるため出さない）。
        System.out.println("書き方の決まりの読み書きに失敗しました：" + e.getClass().getSimpleName());
        return new Unavailable(e);
    }
}
