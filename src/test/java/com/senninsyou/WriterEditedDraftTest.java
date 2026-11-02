package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 本人が直した下書きを、元の下書きとは別の1件（完了）として残す処理の試験。
// AIController は本物のコードを使い、下書きの保存先だけを記録用の偽物に差し替える。
// OpenAI には送らない（AIService を入れないので、呼べば失敗する）。本番DBにもつながない。実行: mvn test
class WriterEditedDraftTest {

    private final List<Map<String, String>> saved = new ArrayList<>();
    private final List<String> otherWrites = new ArrayList<>();
    private long nextId = 77;
    private AIController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = (AIController) unsafe().allocateInstance(AIController.class);
        set(controller, "writerJobRepository", new WriterJobRepository("", "", "") {
            @Override public long saveEditedDraft(Map<String, String> draft) { saved.add(new LinkedHashMap<>(draft)); return nextId; }
            @Override public long createJob(String theme, String audience, String price, String sourceNotes, String interviewNotes) {
                otherWrites.add("createJob"); return -1;
            }
            @Override public void markWriting(long id) { otherWrites.add("markWriting " + id); }
            @Override public void saveResult(long id, Map<String, String> sections) { otherWrites.add("saveResult " + id); }
            @Override public void saveFailure(long id, String errorCode) { otherWrites.add("saveFailure " + id); }
        });
    }

    @Test
    void 直した内容を新しい1件として残し_元の下書きは書き換えない() {
        Map<String, String> result = controller.saveEditedWriterJob(request(
                "  直したタイトル  ", "直した無料部分", "直した有料部分", "直した説明", "直した投稿案", "確認済み"));

        assertEquals(Map.of("id", "77", "status", "完了"), result);
        assertEquals(1, saved.size());
        Map<String, String> draft = saved.get(0);
        assertEquals("直したタイトル", draft.get("title"), "前後の空白は取り除く");
        assertEquals("直した無料部分", draft.get("freeSection"));
        assertEquals("直した有料部分", draft.get("paidSection"));
        assertEquals("直した説明", draft.get("salesDescription"));
        assertEquals("直した投稿案", draft.get("snsPost"));
        assertEquals("確認済み", draft.get("reviewNotes"));
        assertEquals("AIで事務作業を減らす", draft.get("theme"));
        assertEquals("個人事業主", draft.get("audience"));
        assertEquals("500円", draft.get("price"));
        assertEquals("実体験の手順", draft.get("sourceNotes"));
        assertEquals("質問：何分減った？\n回答：30分", draft.get("interviewNotes"));
        assertEquals(List.of(), otherWrites, "既存の下書きの更新や、新しい執筆依頼はしない");
    }

    @Test
    void 価格が空なら未定_SNS投稿案と確認事項は空でも保存できる() {
        AIController.EditedWriterRequest request = new AIController.EditedWriterRequest(
                "テーマ", "読者", "  ", "材料", "", "タイトル", "無料", "有料", "説明", "", "");
        assertEquals("完了", controller.saveEditedWriterJob(request).get("status"));
        assertEquals("未定", saved.get(0).get("price"));
        assertEquals("", saved.get(0).get("snsPost"));
        assertEquals("", saved.get(0).get("reviewNotes"));
    }

    @Test
    void 必須の項目が空なら保存しない() {
        for (String missing : List.of("theme", "audience", "sourceNotes", "title", "freeSection", "paidSection", "salesDescription")) {
            Map<String, String> fields = new LinkedHashMap<>(Map.of(
                    "theme", "テーマ", "audience", "読者", "sourceNotes", "材料", "title", "タイトル",
                    "freeSection", "無料", "paidSection", "有料", "salesDescription", "説明"));
            fields.put(missing, "   ");
            AIController.EditedWriterRequest request = new AIController.EditedWriterRequest(
                    fields.get("theme"), fields.get("audience"), "", fields.get("sourceNotes"), "",
                    fields.get("title"), fields.get("freeSection"), fields.get("paidSection"), fields.get("salesDescription"), "", "");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> controller.saveEditedWriterJob(request), missing);
            assertTrue(error.getMessage().endsWith("を入力してください。"), missing + ": " + error.getMessage());
        }
        assertEquals(List.of(), saved, "1件も保存しない");
    }

    @Test
    void 長すぎる項目は保存しない() {
        AIController.EditedWriterRequest request = new AIController.EditedWriterRequest(
                "テーマ", "読者", "", "材料", "", "あ".repeat(301), "無料", "有料", "説明", "", "");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> controller.saveEditedWriterJob(request));
        assertEquals("タイトルが長すぎます。", error.getMessage());
        assertEquals(List.of(), saved);
    }

    @Test
    void DBに保存できなければ_今までと同じ_WAI_DB_を返す() {
        nextId = -1;
        assertEquals(Map.of("errorCode", "WAI-DB"), controller.saveEditedWriterJob(request("タイトル", "無料", "有料", "説明", "", "")));
    }

    private static AIController.EditedWriterRequest request(
            String title, String free, String paid, String sales, String sns, String review) {
        return new AIController.EditedWriterRequest(
                "AIで事務作業を減らす", "個人事業主", "500円", "実体験の手順", "質問：何分減った？\n回答：30分",
                title, free, paid, sales, sns, review);
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
