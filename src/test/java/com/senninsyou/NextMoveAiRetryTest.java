package com.senninsyou;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

// L-1専用のOpenAIの接続で、SDKの中も含めて自動の再試行が0回であることを確かめる。
// 127.0.0.1 で動く偽のOpenAIサーバー（JDKの HttpServer）に向けて呼び、届いた要求の回数を数える。
// 本物のOpenAIには送らない（APIキーも偽物）。費用はかからない。
class NextMoveAiRetryTest {

    // 自動の再試行があれば、SDKは少し待ってから呼び直す。その分を待ってから回数を数える。
    private static final long WAIT_FOR_LATE_RETRY_MS = 3000;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                handler.handle(exchange);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    interface Handler {
        void handle(HttpExchange exchange) throws IOException, InterruptedException;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final String ERROR_BODY = "{\"error\":{\"message\":\"テスト用の失敗\",\"type\":\"server_error\",\"param\":null,\"code\":null}}";

    private NextMoveAi.OpenAi ai(String baseUrl, Duration timeout) {
        return new NextMoveAi.OpenAi(NextMoveAi.OpenAi.client("test-key-not-real", baseUrl, timeout));
    }

    private int askAndCount(String baseUrl, Duration timeout) throws InterruptedException {
        String answer = ai(baseUrl, timeout).ask("試験");
        assertNull(answer, "失敗のときは null");
        Thread.sleep(WAIT_FOR_LATE_RETRY_MS);
        return requests.get();
    }

    @Test
    void サーバーが500を返しても_要求は1回だけ() throws Exception {
        String url = start(exchange -> reply(exchange, 500, ERROR_BODY));
        assertEquals(1, askAndCount(url, Duration.ofSeconds(10)));
    }

    @Test
    void サーバーが429を返しても_要求は1回だけ() throws Exception {
        String url = start(exchange -> reply(exchange, 429, ERROR_BODY));
        assertEquals(1, askAndCount(url, Duration.ofSeconds(10)));
    }

    @Test
    void サーバーが503を返しても_要求は1回だけ() throws Exception {
        String url = start(exchange -> reply(exchange, 503, ERROR_BODY));
        assertEquals(1, askAndCount(url, Duration.ofSeconds(10)));
    }

    @Test
    void 応答の前に接続が切れても_要求は1回だけ() throws Exception {
        String url = start(exchange -> {
            // 何も返さずに閉じる（finally の exchange.close()）
        });
        assertEquals(1, askAndCount(url, Duration.ofSeconds(10)));
    }

    @Test
    void 時間切れになっても_要求は1回だけ() throws Exception {
        String url = start(exchange -> {
            Thread.sleep(4000);
            reply(exchange, 200, "{}");
        });
        assertEquals(1, askAndCount(url, Duration.ofSeconds(1)));
    }

    @Test
    void 正常な返答なら_要求1回で文を受け取る() throws Exception {
        String text = "{\\\"nextMove\\\":\\\"記事を出す\\\"}";
        String body = """
                {"id":"resp_test","object":"response","created_at":0,"status":"completed","model":"gpt-5.2",
                 "output":[{"type":"message","id":"msg_test","status":"completed","role":"assistant",
                   "content":[{"type":"output_text","text":"%s","annotations":[]}]}],
                 "error":null,"incomplete_details":null,"instructions":null,"metadata":{},
                 "parallel_tool_calls":false,"temperature":1.0,"tool_choice":"auto","tools":[],"top_p":1.0}
                """.formatted(text);
        String url = start(exchange -> reply(exchange, 200, body));

        String answer = ai(url, Duration.ofSeconds(10)).ask("試験");

        assertEquals("{\"nextMove\":\"記事を出す\"}", answer);
        assertEquals(1, requests.get());
    }

    // 対照：再試行の回数を指定しない接続（今の会話のAI将軍と同じ作り方）は、同じ500で呼び直す。
    // この試験の数え方で再試行を見つけられること、SDKの既定が呼び直す設定であることを、ローカルだけで確かめる。
    @Test
    void 対照_再試行の回数を指定しない接続は_500のとき呼び直す() throws Exception {
        String url = start(exchange -> reply(exchange, 500, ERROR_BODY));
        OpenAIClient defaultClient = OpenAIOkHttpClient.builder()
                .apiKey("test-key-not-real")
                .baseUrl(url)
                .timeout(Duration.ofSeconds(10))
                .build();

        String answer = new NextMoveAi.OpenAi(defaultClient).ask("試験");
        Thread.sleep(WAIT_FOR_LATE_RETRY_MS);

        assertNull(answer);
        assertTrue(requests.get() > 1, "既定の接続は呼び直す（実際の回数: " + requests.get() + "）");
    }
}
