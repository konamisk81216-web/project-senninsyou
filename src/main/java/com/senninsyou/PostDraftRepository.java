package com.senninsyou;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PostDraftRepository {

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public PostDraftRepository(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    public void addDraft(String content, String intent) {
        String sql = "INSERT INTO post_drafts (content, intent) VALUES (?, ?)";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, content);
            statement.setString(2, intent);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("投稿案の保存に失敗しました。");
            e.printStackTrace();
        }
    }

    public void updateStatus(long id, String status) {
        String sql = "UPDATE post_drafts SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, status);
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("投稿案の状態更新に失敗しました。");
            e.printStackTrace();
        }
    }

    public List<Map<String, String>> getDrafts() {
        List<Map<String, String>> drafts = new ArrayList<>();
        String sql = """
                SELECT * FROM post_drafts
                WHERE status <> '却下'
                ORDER BY id DESC
                LIMIT 30
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            while (result.next()) {
                Map<String, String> draft = new LinkedHashMap<>();
                draft.put("id", String.valueOf(result.getLong("id")));
                draft.put("status", result.getString("status"));
                draft.put("content", result.getString("content"));
                draft.put("intent", result.getString("intent"));
                drafts.add(draft);
            }
        } catch (Exception e) {
            System.out.println("投稿案の取得に失敗しました。");
            e.printStackTrace();
        }
        return drafts;
    }

    // 同じ内容を作り続けないよう、直近に作った投稿をAIへ渡す。
    public String buildRecentPostsText() {
        List<Map<String, String>> drafts = getDrafts();
        if (drafts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        drafts.stream().limit(10).forEach(draft ->
                text.append("・").append(draft.get("content")).append("\n"));
        return text.toString().trim();
    }
}
