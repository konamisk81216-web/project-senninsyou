package com.senninsyou;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// L-1の、AI将軍の返答を読み、決まりに合うかを確かめる。合わなければ直さずに失敗にする（提案は出さない）。
// 確かめるのは次の2つだけ。これでAIの説明文すべての正しさを保証するわけではない。
// ・根拠の番号が、AIに渡した番号の中にあるか（別の案件の番号が混ざっていないか）
// ・金額・件数を書いていないか（金額・件数・未記録の表示はサーバーの根拠の欄だけで出す）
record NextMoveAnswer(String nextMove, List<Reason> reasons, String missingInfo) {

    record Reason(String text, List<String> evidenceIds) {
    }

    static final int MAX_NEXT_MOVE = 500;
    static final int MAX_REASON = 400;
    static final int MAX_REASONS = 6;
    static final int MAX_MISSING_INFO = 500;

    // 数字＋円・件・％、¥や￥のあとの数字、ゼロ円・零円。漢数字の金額（「千円」など）までは見ない。
    static final Pattern AMOUNT_OR_COUNT = Pattern.compile(
            "[0-9０-９][0-9０-９,，.．]*\\s*(円|件|%|％)|[¥￥]\\s*[0-9０-９]|(ゼロ|零)\\s*円");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final class InvalidAnswerException extends Exception {
        InvalidAnswerException(String message) {
            super(message);
        }
    }

    static NextMoveAnswer parse(String raw, Set<String> allowedEvidenceIds) throws InvalidAnswerException {
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new InvalidAnswerException("返答をJSONとして読めませんでした");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidAnswerException("返答の形が正しくありませんでした");
        }
        // 決めた3項目以外（実行の操作 action など）が入っていたら受け取らない。
        onlyFields(root, NextMovePrompt.ANSWER_FIELDS);

        String nextMove = text(root, "nextMove", MAX_NEXT_MOVE, true);
        String missingInfo = text(root, "missingInfo", MAX_MISSING_INFO, false);

        JsonNode reasonsNode = root.get("reasons");
        if (reasonsNode == null || !reasonsNode.isArray() || reasonsNode.isEmpty()) {
            throw new InvalidAnswerException("根拠がありませんでした");
        }
        if (reasonsNode.size() > MAX_REASONS) {
            throw new InvalidAnswerException("根拠の数が多すぎました");
        }
        List<Reason> reasons = new ArrayList<>();
        for (JsonNode reasonNode : reasonsNode) {
            if (!reasonNode.isObject()) {
                throw new InvalidAnswerException("根拠の形が正しくありませんでした");
            }
            onlyFields(reasonNode, NextMovePrompt.REASON_FIELDS);
            String text = text(reasonNode, "text", MAX_REASON, true);
            JsonNode idsNode = reasonNode.get("evidenceIds");
            if (idsNode == null || !idsNode.isArray() || idsNode.isEmpty()) {
                throw new InvalidAnswerException("根拠の番号がありませんでした");
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode idNode : idsNode) {
                if (!idNode.isTextual() || !allowedEvidenceIds.contains(idNode.asText())) {
                    throw new InvalidAnswerException("渡していない根拠の番号が含まれていました");
                }
                ids.add(idNode.asText());
            }
            reasons.add(new Reason(text, List.copyOf(ids)));
        }

        for (String value : allTexts(nextMove, reasons, missingInfo)) {
            if (AMOUNT_OR_COUNT.matcher(value).find()) {
                throw new InvalidAnswerException("提案の文に金額または件数が含まれていました");
            }
        }
        return new NextMoveAnswer(nextMove, List.copyOf(reasons), missingInfo);
    }

    private static String text(JsonNode node, String field, int max, boolean required) throws InvalidAnswerException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidAnswerException("返答の項目 " + field + " がありませんでした");
        }
        String text = value.asText().strip();
        if (required && text.isEmpty()) {
            throw new InvalidAnswerException("返答の項目 " + field + " が空でした");
        }
        if (text.length() > max) {
            throw new InvalidAnswerException("返答の項目 " + field + " が長すぎました");
        }
        return text;
    }

    private static void onlyFields(JsonNode node, List<String> allowed) throws InvalidAnswerException {
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw new InvalidAnswerException("返答に決めていない項目が含まれていました");
            }
        }
    }

    private static List<String> allTexts(String nextMove, List<Reason> reasons, String missingInfo) {
        List<String> texts = new ArrayList<>();
        texts.add(nextMove);
        reasons.forEach(reason -> texts.add(reason.text()));
        texts.add(missingInfo);
        return texts;
    }
}
