package com.senninsyou;

import java.time.Duration;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStatus;

// L-1でAI将軍を呼ぶ部分。試験では偽物に差し替える。
interface NextMoveAi {

    // APIキーがなく呼べないときは false（このときは実行記録も始めない）。
    boolean available();

    // 1回だけ呼ぶ。返答が取れなければ null。呼び直しはしない。
    String ask(String prompt);

    // L-1専用のOpenAIの接続。会話のAI将軍（AIService）とは別の接続で、AIServiceの設定は変えない。
    // ・自動の再試行は0回（maxRetries(0)）。SDKの既定は2回で、通信エラー・429・5xxなどのときにSDKの中で呼び直すため、
    //   L-1では明示して止める。アプリ側でも呼び直さない。
    // ・時間切れの上限を決める（既定より短くし、返答待ちが長く続かないようにする）。
    final class OpenAi implements NextMoveAi {

        static final Duration TIMEOUT = Duration.ofSeconds(90);

        private final OpenAIClient client;

        OpenAi(OpenAIClient client) {
            this.client = client;
        }

        static OpenAi fromEnvironment() {
            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                return new OpenAi(null);
            }
            return new OpenAi(client(apiKey, null, TIMEOUT));
        }

        // baseUrl はローカルの偽のサーバーで試験するときだけ指定する（null なら本物のOpenAI）。
        static OpenAIClient client(String apiKey, String baseUrl, Duration timeout) {
            OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .maxRetries(0)
                    .timeout(timeout);
            if (baseUrl != null) {
                builder.baseUrl(baseUrl);
            }
            return builder.build();
        }

        static ResponseCreateParams params(String prompt) {
            return ResponseCreateParams.builder()
                    .input(prompt)
                    .model(ChatModel.GPT_5_2)
                    .text(NextMovePrompt.answerFormat())
                    .build();
        }

        @Override
        public boolean available() {
            return client != null;
        }

        @Override
        public String ask(String prompt) {
            if (client == null) {
                return null;
            }
            try {
                Response response = client.responses().create(params(prompt));
                // 途中で終わった返答や状態の分からない返答は使わない。
                if (!response.status().map(ResponseStatus.COMPLETED::equals).orElse(false)) {
                    System.out.println("収益機会の次の一手：AI将軍の返答が完了していません。");
                    return null;
                }
                return response.output().stream()
                        .flatMap(item -> item.message().stream())
                        .flatMap(message -> message.content().stream())
                        .flatMap(content -> content.outputText().stream())
                        .map(outputText -> outputText.text())
                        .findFirst()
                        .orElse(null);
            } catch (Exception e) {
                System.out.println("収益機会の次の一手：AI将軍との通信に失敗しました。");
                return null;
            }
        }
    }
}
