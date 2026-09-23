package com.senninsyou;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AuthorFactRepository {

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public AuthorFactRepository(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    public void addFact(String category, String topic, String fact) {
        String sql = "INSERT INTO author_facts (category, topic, fact) VALUES (?, ?, ?)";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, category);
            statement.setString(2, topic);
            statement.setString(3, fact);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("事実の保存に失敗しました。");
            e.printStackTrace();
        }
    }

    public void deleteFact(long id) {
        String sql = "DELETE FROM author_facts WHERE id = ?";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("事実の削除に失敗しました。");
            e.printStackTrace();
        }
    }

    public List<Map<String, String>> getAllFacts() {
        List<Map<String, String>> facts = new ArrayList<>();
        String sql = "SELECT * FROM author_facts ORDER BY category, id";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()
        ) {
            while (result.next()) {
                Map<String, String> fact = new LinkedHashMap<>();
                fact.put("id", String.valueOf(result.getLong("id")));
                fact.put("category", result.getString("category"));
                fact.put("topic", result.getString("topic"));
                fact.put("fact", result.getString("fact"));
                facts.add(fact);
            }
        } catch (Exception e) {
            System.out.println("事実の取得に失敗しました。");
            e.printStackTrace();
        }
        return facts;
    }

    // AIへ渡す形。すでに知っていることとして提示し、重複した質問を防ぐ。
    public String buildKnownFactsText() {
        List<Map<String, String>> facts = getAllFacts();
        if (facts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Map<String, String> fact : facts) {
            text.append("・[")
                .append(fact.get("category"))
                .append("] ")
                .append(fact.get("topic"))
                .append("：")
                .append(fact.get("fact"))
                .append("\n");
        }
        return text.toString().trim();
    }
}
