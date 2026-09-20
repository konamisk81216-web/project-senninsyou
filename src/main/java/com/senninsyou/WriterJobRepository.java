package com.senninsyou;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import java.util.LinkedHashMap;
import java.util.Map;

public class WriterJobRepository {

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public WriterJobRepository(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    public long createJob(
            String theme,
            String audience,
            String price,
            String sourceNotes,
            String interviewNotes) {

        String sql = """
                INSERT INTO writer_jobs
                    (theme, audience, price, source_notes, interview_notes)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, theme);
            statement.setString(2, audience);
            statement.setString(3, price);
            statement.setString(4, sourceNotes);
            statement.setString(5, interviewNotes);

            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getLong("id");
                }
            }
        } catch (Exception e) {
            System.out.println("執筆依頼の登録に失敗しました。");
            e.printStackTrace();
        }
        return -1;
    }

    public void markWriting(long id) {
        updateStatus(id, "執筆中");
    }

    public void saveResult(long id, Map<String, String> sections) {
        String sql = """
                UPDATE writer_jobs SET
                    status = '完了',
                    title = ?,
                    free_section = ?,
                    paid_section = ?,
                    sales_description = ?,
                    sns_post = ?,
                    review_notes = ?,
                    error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, sections.get("title"));
            statement.setString(2, sections.get("freeSection"));
            statement.setString(3, sections.get("paidSection"));
            statement.setString(4, sections.get("salesDescription"));
            statement.setString(5, sections.get("snsPost"));
            statement.setString(6, sections.get("reviewNotes"));
            statement.setLong(7, id);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("執筆結果の保存に失敗しました。");
            e.printStackTrace();
        }
    }

    public void saveFailure(long id, String errorCode) {
        String sql = """
                UPDATE writer_jobs SET
                    status = '失敗',
                    error_code = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, errorCode);
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("執筆失敗の記録に失敗しました。");
            e.printStackTrace();
        }
    }

    public Map<String, String> findJob(long id) {
        return findOne("SELECT * FROM writer_jobs WHERE id = ?", id);
    }

    public Map<String, String> findLatestJob() {
        return findOne("SELECT * FROM writer_jobs ORDER BY id DESC LIMIT 1", null);
    }

    public Map<String, String> findUnfinishedInputs(long id) {
        Map<String, String> job = findJob(id);
        if (job.isEmpty()) {
            return job;
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("theme", job.get("theme"));
        inputs.put("audience", job.get("audience"));
        inputs.put("price", job.get("price"));
        inputs.put("sourceNotes", job.get("sourceNotes"));
        inputs.put("interviewNotes", job.get("interviewNotes"));
        return inputs;
    }

    private void updateStatus(long id, String status) {
        String sql = "UPDATE writer_jobs SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, status);
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (Exception e) {
            System.out.println("執筆状態の更新に失敗しました。");
            e.printStackTrace();
        }
    }

    private Map<String, String> findOne(String sql, Long id) {
        Map<String, String> job = new LinkedHashMap<>();

        try (
            Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            if (id != null) {
                statement.setLong(1, id);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return job;
                }
                job.put("id", String.valueOf(result.getLong("id")));
                job.put("status", result.getString("status"));
                job.put("theme", text(result.getString("theme")));
                job.put("audience", text(result.getString("audience")));
                job.put("price", text(result.getString("price")));
                job.put("sourceNotes", text(result.getString("source_notes")));
                job.put("interviewNotes", text(result.getString("interview_notes")));
                job.put("title", text(result.getString("title")));
                job.put("freeSection", text(result.getString("free_section")));
                job.put("paidSection", text(result.getString("paid_section")));
                job.put("salesDescription", text(result.getString("sales_description")));
                job.put("snsPost", text(result.getString("sns_post")));
                job.put("reviewNotes", text(result.getString("review_notes")));
                job.put("errorCode", text(result.getString("error_code")));
            }
        } catch (Exception e) {
            System.out.println("執筆依頼の取得に失敗しました。");
            e.printStackTrace();
        }
        return job;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
