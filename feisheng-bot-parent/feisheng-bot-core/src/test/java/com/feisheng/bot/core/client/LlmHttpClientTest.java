package com.feisheng.bot.core.client;
import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.dto.LlmFailureType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LlmHttpClientTest {

    private HttpServer server;
    private LlmHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        client = new LlmHttpClient();
        ReflectionTestUtils.setField(client, "connectTimeout", 1_000);
        ReflectionTestUtils.setField(client, "readTimeout", 1_000);
        ReflectionTestUtils.setField(client, "maxRetries", 1);
        ReflectionTestUtils.setField(client, "temperature", 0D);
        ReflectionTestUtils.setField(client, "maxOutputTokens", 128);
        ReflectionTestUtils.setField(client, "deepSeekThinkingEnabled", false);
        ReflectionTestUtils.setField(client, "localKeepAlive", -1L);
        ReflectionTestUtils.setField(client, "defaultSystemPrompt", "system");
        client.init();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void classifiesSchemaRejectionSeparatelyFromServiceFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 400, "{\"error\":{\"message\":\"uniqueItems is unsupported\"}}");
        });
        server.start();

        ChatResponse response = client.callJsonSchemaWithPolicy(endpoint(), "key", "model",
            "system", "prompt", "dashscope", Map.of("type", "object"), 500, 0);

        assertFalse(response.isSuccess());
        assertEquals(LlmFailureType.SCHEMA_UNSUPPORTED, response.getFailureType());
        assertEquals(1, requests.get());
    }

    @Test
    void classifiesProviderFreeQuotaExhaustionAsRateLimit() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 403,
                "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\","
                        + "\"message\":\"Free quota exhausted\"}}"));
        server.start();

        ChatResponse response = client.callWithPolicy(endpoint(), "key", "model", "system",
                "prompt", "dashscope", 500, 0);

        assertFalse(response.isSuccess());
        assertEquals(LlmFailureType.RATE_LIMIT, response.getFailureType());
    }

    @Test
    void classifiesReadTimeoutWithoutRetryingWhenPolicyDisablesRetries() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(1_000L);
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        ChatResponse response = client.callWithPolicy(endpoint(), "key", "model", "system",
            "prompt", "dashscope", 50, 0);

        assertFalse(response.isSuccess());
        assertEquals(LlmFailureType.TIMEOUT, response.getFailureType());
        assertEquals(1, requests.get());
    }

    @Test
    void classifiesBlankSuccessfulContentAsInvalidOutput() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}"));
        server.start();

        ChatResponse response = client.callWithPolicy(endpoint(), "key", "model", "system",
                "prompt", "test", 500, 0);

        assertFalse(response.isSuccess());
        assertEquals(LlmFailureType.INVALID_OUTPUT, response.getFailureType());
    }

    @Test
    void classifiesMalformedSuccessfulProviderBodyAsInvalidOutput() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200,
                "{\"choices\":\"not-an-array\"}"));
        server.start();

        ChatResponse response = client.callWithPolicy(endpoint(), "key", "model", "system",
                "prompt", "test", 500, 0);

        assertFalse(response.isSuccess());
        assertEquals(LlmFailureType.INVALID_OUTPUT, response.getFailureType());
    }


    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
