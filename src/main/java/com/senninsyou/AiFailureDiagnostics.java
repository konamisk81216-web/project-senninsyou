package com.senninsyou;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.openai.errors.OpenAIServiceException;

// AIとの通信に失敗したときの、原因を見分けるための短い診断。
// 出すのは「例外の種類（クラス名）」「原因の例外の種類」「HTTPの状態」「OpenAIのエラーコードと種別」だけ。
// 例外のメッセージ・本文・ヘッダーは出さない（OpenAIのエラーメッセージにはAPIキーの一部が入ることがあるため）。
// 会話の本文やAPIキーはここに渡さない。
final class AiFailureDiagnostics {

    // エラーコードと種別は、下の既知の値と完全に一致したときだけ出す。
    // 知らない値は、文字の形にかかわらず固定の文言に置き換える（空白のないAPIキーなども、形では見分けられないため）。
    // 新しい値を出したくなったら、ここに足す。
    private static final Set<String> KNOWN_CODES = Set.of(
            "invalid_api_key", "invalid_organization", "account_deactivated",
            "rate_limit_exceeded", "insufficient_quota", "billing_hard_limit_reached",
            "model_not_found", "context_length_exceeded", "unsupported_country_region_territory",
            "invalid_value", "invalid_type", "missing_required_parameter",
            "unsupported_parameter", "unsupported_value", "server_error");
    private static final Set<String> KNOWN_TYPES = Set.of(
            "invalid_request_error", "authentication_error", "permission_error", "not_found_error",
            "rate_limit_error", "insufficient_quota", "requests", "tokens",
            "server_error", "api_error");
    static final String UNKNOWN_VALUE = "（既知でない値）";
    private static final int MAX_CAUSES = 4;

    private AiFailureDiagnostics() {
    }

    static String describe(Throwable failure) {
        if (failure == null) {
            return "種類=なし";
        }
        StringBuilder text = new StringBuilder("種類=").append(typeName(failure));
        List<String> causes = causeNames(failure);
        if (!causes.isEmpty()) {
            text.append("、原因=").append(String.join("←", causes));
        }
        if (failure instanceof OpenAIServiceException service) {
            text.append("、HTTP状態=").append(service.statusCode());
            text.append("、エラーコード=").append(known(service.code(), KNOWN_CODES));
            text.append("、エラー種別=").append(known(service.type(), KNOWN_TYPES));
        } else {
            text.append("、HTTP状態=なし");
        }
        return text.toString();
    }

    // 原因の例外の種類だけを、近い順に並べる（メッセージは出さない）。
    private static List<String> causeNames(Throwable failure) {
        List<String> names = new ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        seen.put(failure, true);
        Throwable cause = failure.getCause();
        while (cause != null && !seen.containsKey(cause) && names.size() < MAX_CAUSES) {
            seen.put(cause, true);
            names.add(typeName(cause));
            cause = cause.getCause();
        }
        return names;
    }

    private static String typeName(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        return name.isEmpty() ? failure.getClass().getName() : name;
    }

    private static String known(Optional<String> value, Set<String> knownValues) {
        try {
            if (value == null || value.isEmpty()) {
                return "なし";
            }
            String text = value.get();
            return knownValues.contains(text) ? text : UNKNOWN_VALUE;
        } catch (RuntimeException e) {
            return "（取得できない）";
        }
    }
}
