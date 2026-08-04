package com.feisheng.bot.core.service;

import com.feisheng.bot.core.client.AdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingServiceTest {
    @Test
    void retriesTransientQueryEmbeddingFailure() {
        AdminClient adminClient = mock(AdminClient.class);
        RestTemplate rest = mock(RestTemplate.class);
        when(adminClient.getActiveModels()).thenReturn(List.of(Map.of(
            "status", 1,
            "modelType", "Embedding",
            "apiUrl", "https://api.example.com/v1/embeddings",
            "apiKey", "test-key",
            "modelName", "embedding-3")));

        AtomicInteger calls = new AtomicInteger();
        when(rest.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
            .thenAnswer(invocation -> {
                if (calls.getAndIncrement() == 0) throw new ResourceAccessException("timeout");
                return ResponseEntity.ok(Map.of("data", List.of(
                    Map.of("index", 0, "embedding", List.of(0.1, 0.2)))));
            });

        EmbeddingService service = new EmbeddingService(adminClient, rest, 3, 0, 32);
        List<Double> result = service.embed("介绍一下你们公司的产品");

        assertEquals(2, calls.get());
        assertEquals(List.of(0.1, 0.2), result);
    }
}
