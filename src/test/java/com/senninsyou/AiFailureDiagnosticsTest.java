package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;

// AI将軍との通信に失敗したときの診断の1行で、認証エラー・利用制限・サーバーエラー・接続失敗を見分けられることを確かめる。
// 診断には例外の種類・HTTPの状態・エラーコードと種別だけを出し、APIキー・認証ヘッダー・会話の本文・エラーメッセージは出さない。
// OpenAI の代わりに 127.0.0.1 の偽のサーバーを使う。本物の OpenAI にはつながない。実行: mvn test
// 試験の client は再試行0回にしている（偽のサーバーへの依頼を数えるため）。本番の client の再試行の設定は変えていない。
class AiFailureDiagnosticsTest {

    private static final String API_KEY = "sk-test-diagnostics-SECRET-0123456789";
    private static final String PROMPT = "秘密の相談本文：売上の数字をここに書きます";

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void 認証エラーは401とinvalid_api_keyで見分けられ_キーとメッセージは出さない() throws Exception {
        String out = failWith(401, error("Incorrect API key provided: " + API_KEY + ". You can find your API key at https://platform.openai.com.",
                "invalid_request_error", "invalid_api_key"));
        assertEquals("AI将軍との通信の診断：種類=UnauthorizedException、HTTP状態=401、エラーコード=invalid_api_key、エラー種別=invalid_request_error",
                diagnosis(out));
        assertSafe(out, "Incorrect API key provided");
    }

    @Test
    void 利用制限は429とrate_limit_exceededで見分けられる() throws Exception {
        String out = failWith(429, error("Rate limit reached for gpt-5.2 in organization org-SECRETORG on requests per min (RPM).",
                "requests", "rate_limit_exceeded"));
        assertEquals("AI将軍との通信の診断：種類=RateLimitException、HTTP状態=429、エラーコード=rate_limit_exceeded、エラー種別=requests",
                diagnosis(out));
        assertSafe(out, "org-SECRETORG");
    }

    @Test
    void 利用枠の不足も429のinsufficient_quotaとして利用制限と見分けられる() throws Exception {
        String out = failWith(429, error("You exceeded your current quota, please check your plan and billing details.",
                "insufficient_quota", "insufficient_quota"));
        assertEquals("AI将軍との通信の診断：種類=RateLimitException、HTTP状態=429、エラーコード=insufficient_quota、エラー種別=insufficient_quota",
                diagnosis(out));
        assertSafe(out, "exceeded your current quota");
    }

    @Test
    void サーバーエラーは500とserver_errorで見分けられる_コードがなければなしと出す() throws Exception {
        String out = failWith(500, "{\"error\":{\"message\":\"The server had an error while processing your request.\","
                + "\"type\":\"server_error\",\"param\":null,\"code\":null}}");
        assertEquals("AI将軍との通信の診断：種類=InternalServerException、HTTP状態=500、エラーコード=なし、エラー種別=server_error",
                diagnosis(out));
        assertSafe(out, "The server had an error");
    }

    @Test
    void 接続失敗はHTTP状態なしと原因の例外の種類で見分けられる() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        String out = run("http://127.0.0.1:" + closedPort + "/v1");
        String line = diagnosis(out);
        assertTrue(line.startsWith("AI将軍との通信の診断：種類=OpenAIIoException、原因="), line);
        assertTrue(line.contains("ConnectException"), "接続できなかったことが原因の種類で分かる：" + line);
        assertTrue(line.endsWith("、HTTP状態=なし"), line);
        assertSafe(out, "127.0.0.1:" + closedPort);
    }

    @Test
    void 想定外の形のエラーコードは中身を出さない() throws Exception {
        String out = failWith(400, "{\"error\":{\"message\":\"bad\",\"type\":\"invalid request " + API_KEY + "\","
                + "\"code\":\"Authorization: Bearer " + API_KEY + "\"}}");
        assertEquals("AI将軍との通信の診断：種類=BadRequestException、HTTP状態=400、エラーコード=（既知でない値）、エラー種別=（既知でない値）",
                diagnosis(out));
        assertSafe(out, "bad");
    }

    @Test
    void 空白を含まない架空のAPIキーをcodeとtypeに入れても出さない() throws Exception {
        // 英数字と - だけの、ふつうのエラーコードと同じ形の値。形では見分けられないので、既知の値でなければ出さない。
        String out = failWith(401, "{\"error\":{\"message\":\"denied\",\"type\":\"" + API_KEY + "\",\"param\":null,"
                + "\"code\":\"" + API_KEY + "\"}}");
        assertEquals("AI将軍との通信の診断：種類=UnauthorizedException、HTTP状態=401、エラーコード=（既知でない値）、エラー種別=（既知でない値）",
                diagnosis(out));
        assertSafe(out, "denied");
    }

    @Test
    void 形が正しくても知らないエラーコードと種別は固定の文言に置き換える() throws Exception {
        String out = failWith(400, error("bad request", "brand_new_error_type", "brand_new_code_2027"));
        assertEquals("AI将軍との通信の診断：種類=BadRequestException、HTTP状態=400、エラーコード=（既知でない値）、エラー種別=（既知でない値）",
                diagnosis(out));
        assertFalse(out.contains("brand_new"), "知らない値の中身は出さない");
    }

    // ===== 道具 =====

    private String failWith(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String out = run("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        assertEquals(1, requests.get(), "偽のサーバーへの依頼は1回（試験の client は再試行0回）");
        return out;
    }

    // 本物の AIService.askGeneral を呼び、その間に標準出力へ書かれた内容を返す。
    private static String run(String baseUrl) throws Exception {
        AIService ai = new AIService();
        set(ai, "client", OpenAIOkHttpClient.builder().apiKey(API_KEY).baseUrl(baseUrl).maxRetries(0).build());
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        String answer;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            answer = ai.askGeneral(PROMPT, true);
        } finally {
            System.setOut(original);
        }
        assertNull(answer, "失敗なので返答はない（今までどおり）");
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("AI将軍との通信に失敗しました。"), "今までの2行もそのまま出る");
        assertTrue(out.contains("ネット接続やAPI設定を確認してください。"));
        return out;
    }

    private static String diagnosis(String out) {
        List<String> lines = out.lines().filter(line -> line.startsWith("AI将軍との通信の診断：")).toList();
        assertEquals(1, lines.size(), "診断は1行：" + out);
        System.out.println("[試験で出た診断] " + lines.get(0));   // 記録用（試験の結果の xml に残る）
        return lines.get(0);
    }

    // キー・認証ヘッダー・会話の本文・エラーメッセージ（例外の全文）が出ていないこと。
    private static void assertSafe(String out, String messageFragment) {
        assertFalse(out.contains(API_KEY), "APIキーを出さない");
        assertFalse(out.contains("SECRET"), "キーや組織IDの一部も出さない");
        assertFalse(out.contains("Bearer"), "認証ヘッダーを出さない");
        assertFalse(out.contains("Authorization"), "認証ヘッダーを出さない");
        assertFalse(out.contains("秘密の相談本文"), "会話の本文を出さない");
        assertFalse(out.contains(messageFragment), "エラーメッセージ（例外の全文）を出さない");
        assertFalse(out.contains("at com.") || out.contains("at okhttp3"), "スタックトレースを出さない");
    }

    private static String error(String message, String type, String code) {
        return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"" + type + "\",\"param\":null,\"code\":\"" + code + "\"}}";
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
