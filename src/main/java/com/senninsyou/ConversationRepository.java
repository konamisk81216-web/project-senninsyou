package com.senninsyou;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConversationRepository {

    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public ConversationRepository(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    // 会話の保存に失敗しても、返答自体は返せるようにする。
    public void add(String role, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String trimmed = message.length() > MAX_MESSAGE_LENGTH
                ? message.substring(0, MAX_MESSAGE_LENGTH)
                : message;

        String sql = "INSERT INTO conversations (role, message) VALUES (?, ?)";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, role);
            statement.setString(2, trimmed);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("会話の保存に失敗しました。");
        }
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
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
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
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement("DELETE FROM conversations")
        ) {
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("会話の削除に失敗しました。");
        }
    }
}
