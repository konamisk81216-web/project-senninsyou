package com.senninsyou;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConversationRepository {

    private static final int MAX_MESSAGE_LENGTH = 4000;

    static final String INSERT_SQL = "INSERT INTO conversations (role, message) VALUES (?, ?)";

    interface ConnectionOpener {
        Connection open() throws SQLException;
    }

    // 相談の1往復を保存した結果。
    enum ExchangeSave {
        SAVED,      // 確定した（発言と返答がそろって保存された）
        NOT_SAVED,  // 保存しなかった（本文が空で書かなかった、または確定する前に失敗して取り消した。どちらも保存されていない）
        UNKNOWN     // 確定（commit）の結果が分からない。保存されたかもしれないので、保存されなかったとは扱わない
    }

    private final ConnectionOpener connections;

    public ConversationRepository(String jdbcUrl, String user, String password) {
        this(() -> DriverManager.getConnection(jdbcUrl, user, password));
    }

    ConversationRepository(ConnectionOpener connections) {
        this.connections = connections;
    }

    // 会話の保存に失敗しても、返答自体は返せるようにする。
    // 1件だけの保存（タスク案の整理の要約）。今までどおり、1件を自動確定で書く。
    public void add(String role, String message) {
        String trimmed = normalized(message);
        if (trimmed == null) {
            return;
        }

        try (
            Connection connection = connections.open();
            PreparedStatement statement = connection.prepareStatement(INSERT_SQL)
        ) {
            statement.setString(1, role);
            statement.setString(2, trimmed);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("会話の保存に失敗しました。");
        }
    }

    // 相談の1往復（利用者の発言とAI将軍の返答）を、同じ接続・同じトランザクションでまとめて保存する。
    // ・どちらかの本文が空なら、接続を開かず、両方とも保存しない（片方だけの往復は残さない）。
    // ・2件とも、それぞれ1行ずつ書けたときだけ確定する。更新件数が1でない・失敗したときは取り消し、どちらも残さない。
    // ・確定の結果が分からないとき（commit で例外）は UNKNOWN。保存されなかったとは言い切らない。
    // ・例外は投げない（返答は返せるように）。失敗しても、ここでも呼び出し元でも保存し直さない。
    public ExchangeSave addExchange(String userMessage, String aiMessage) {
        String user = normalized(userMessage);
        String ai = normalized(aiMessage);
        if (user == null || ai == null) {
            System.out.println("会話を保存しませんでした（発言か返答の本文が空です）。");
            return ExchangeSave.NOT_SAVED;
        }
        List<String[]> rows = List.of(new String[] {"利用者", user}, new String[] {"AI将軍", ai});

        Connection connection;
        try {
            connection = connections.open();
        } catch (Exception e) {
            System.out.println("会話の保存に失敗しました（接続できませんでした）。");
            return ExchangeSave.NOT_SAVED;
        }

        boolean committing = false;
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                for (String[] row : rows) {
                    statement.setString(1, row[0]);
                    statement.setString(2, row[1]);
                    int updated = statement.executeUpdate();
                    if (updated != 1) {
                        // 1行書けていなければ、確定しない（下で取り消す）。
                        throw new SQLException(row[0] + "の行を1件だけ書けませんでした（更新件数 " + updated + "）。");
                    }
                }
            }
            committing = true;
            connection.commit();
            return ExchangeSave.SAVED;
        } catch (Exception e) {
            if (committing) {
                System.out.println("会話の保存の確定結果を確認できませんでした（保存されたかは不明）。");
                return ExchangeSave.UNKNOWN;
            }
            try {
                connection.rollback();
            } catch (Exception rollbackFailure) {
                // 確定はしていないので、接続を閉じればサーバー側でも取り消される。
            }
            System.out.println("会話の保存に失敗しました（発言と返答の両方を取り消しました）。");
            return ExchangeSave.NOT_SAVED;
        } finally {
            try {
                connection.close();
            } catch (Exception closeFailure) {
                // 閉じられなくても、結果は上で決まっている。
            }
        }
    }

    // 空の本文は保存しない。長い本文は先頭の4000文字だけにする（今までと同じ）。
    private static String normalized(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return message.length() > MAX_MESSAGE_LENGTH
                ? message.substring(0, MAX_MESSAGE_LENGTH)
                : message;
    }

    public List<Map<String, String>> recent(int limit) {
        List<Map<String, String>> messages = new ArrayList<>();
        String sql = """
                SELECT role, message, created_at FROM (
                    SELECT role, message, created_at, id
                    FROM conversations
                    ORDER BY id DESC
                    LIMIT ?
                ) AS latest
                ORDER BY id
                """;

        try (
            Connection connection = connections.open();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, String> message = new LinkedHashMap<>();
                    message.put("role", result.getString("role"));
                    message.put("message", result.getString("message"));
                    message.put("createdAt", String.valueOf(result.getTimestamp("created_at")));
                    messages.add(message);
                }
            }
        } catch (Exception e) {
            System.out.println("会話の取得に失敗しました。");
        }
        return messages;
    }

    public void clear() {
        try (
            Connection connection = connections.open();
            PreparedStatement statement = connection.prepareStatement("DELETE FROM conversations")
        ) {
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("会話の削除に失敗しました。");
        }
    }
}
