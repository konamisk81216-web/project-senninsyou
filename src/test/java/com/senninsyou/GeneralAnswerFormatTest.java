package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;

// AI将軍への依頼に、返答の形（Structured Outputs）が正しく付いていることを確かめる。
// OpenAI には送らない（依頼を組み立てるだけ）。実行: mvn test
class GeneralAnswerFormatTest {

    @Test
    void 依頼に厳格なJSONの形が付いている() {
        ResponseFormatTextJsonSchemaConfig format = format();

        assertEquals("general_answer", format.name());
        assertEquals(true, format.strict().orElse(false), "形から外れた返答をAPI側で防ぐ");

        Map<String, Object> schema = toMap(format.schema()._additionalProperties());
        assertEquals("object", schema.get("type"));
        assertEquals(false, schema.get("additionalProperties"), "項目を勝手に足させない");
        assertEquals(AIService.GENERAL_ANSWER_FIELDS, schema.get("required"), "全項目が必須");
    }

    @Test
    void 優先度と操作は決められた値だけ() {
        Map<String, Object> properties = properties();

        assertEquals(List.of("高", "中", "低", ""), field(properties, "priority").get("enum"));
        assertEquals(List.of("", "startWriting", "createPosts", "runResearch", "runQuality", "runMarketing"),
                field(properties, "action").get("enum"));
        assertEquals(AIService.GENERAL_ANSWER_FIELDS, List.copyOf(properties.keySet()), "項目の並びも返答形式の説明と同じ");
        properties.values().forEach(value -> assertEquals("string", ((Map<?, ?>) value).get("type")));
    }

    // 上の2つはタスク案の整理の形（11項目、話者の項目なし）。下は相談の形（16項目）。
    @Test
    void 相談の依頼には話者つきの16項目の形が付き_話者は4つの値だけ() {
        ResponseFormatTextJsonSchemaConfig consult = AIService.generalParams("テスト", true)
                .text().orElseThrow().format().orElseThrow().asJsonSchema();
        assertEquals("general_consult_answer", consult.name());
        assertEquals(true, consult.strict().orElse(false));

        Map<String, Object> schema = toMap(consult.schema()._additionalProperties());
        assertEquals(16, AIService.CONSULT_ANSWER_FIELDS.size());
        assertEquals(AIService.CONSULT_ANSWER_FIELDS, schema.get("required"), "16項目すべて必須");
        assertEquals(false, schema.get("additionalProperties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertEquals(AIService.CONSULT_ANSWER_FIELDS, List.copyOf(properties.keySet()));
        assertEquals(List.of("ハル", "ガク", "両方", "確認"), field(properties, "speaker").get("enum"));
        assertEquals(List.of("", "startWriting", "createPosts", "runResearch", "runQuality", "runMarketing"),
                field(properties, "action").get("enum"));
    }

    @Test
    void タスク案の整理の依頼は今までどおり11項目で_話者の項目を持たない() {
        Map<String, Object> properties = properties();
        assertEquals(11, properties.size());
        assertEquals(false, properties.containsKey("speaker"));
        assertEquals(AIService.GENERAL_ANSWER_FIELDS, toMap(AIService.generalParams("テスト", false)
                .text().orElseThrow().format().orElseThrow().asJsonSchema().schema()._additionalProperties()).get("required"));
    }

    private static ResponseFormatTextJsonSchemaConfig format() {
        ResponseCreateParams params = AIService.generalParams("テスト");
        return params.text().orElseThrow().format().orElseThrow().asJsonSchema();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties() {
        return (Map<String, Object>) toMap(format().schema()._additionalProperties()).get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(Map<String, Object> properties, String name) {
        return (Map<String, Object>) properties.get(name);
    }

    // SDK の JsonValue を、普通の Map・List・文字列・真偽値に直して比べやすくする。
    private static Map<String, Object> toMap(Map<String, JsonValue> values) {
        Map<String, Object> plain = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> plain.put(key, value.convert(Object.class)));
        return plain;
    }
}
