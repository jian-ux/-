package com.feisheng.bot.admin.service;

import com.feisheng.bot.admin.entity.BotAiModelConfig;
import com.feisheng.bot.admin.mapper.BotAiModelConfigMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingServiceTest {
    @Test
    void keepsCompleteEmbeddingEndpointUnchanged() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/embeddings",
            EmbeddingService.resolveEmbeddingUrl(
                "https://open.bigmodel.cn/api/paas/v4/embeddings"));
    }

    @Test
    void convertsChatCompletionEndpointToEmbeddingEndpoint() {
        assertEquals(
            "https://api.example.com/v1/embeddings",
            EmbeddingService.resolveEmbeddingUrl(
                "https://api.example.com/v1/chat/completions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void splitsLargeInputsIntoProviderSafeBatches() {
        BotAiModelConfigMapper mapper = mock(BotAiModelConfigMapper.class);
        RestTemplate rest = mock(RestTemplate.class);
        when(mapper.selectList(any())).thenReturn(List.of(model()));

        List<Integer> requestSizes = new ArrayList<>();
        when(rest.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
            .thenAnswer(invocation -> {
                HttpEntity<Map<String, Object>> entity = invocation.getArgument(2);
                List<String> input = (List<String>) entity.getBody().get("input");
                requestSizes.add(input.size());
                List<Map<String, Object>> data = new ArrayList<>();
                for (int i = 0; i < input.size(); i++) {
                    data.add(Map.of("index", i, "embedding", List.of((double) i, 1.0)));
                }
                return ResponseEntity.ok(Map.of("data", data));
            });

        EmbeddingService service = new EmbeddingService(mapper, rest, 32, 1, 0);
        List<String> input = IntStream.range(0, 70).mapToObj(i -> "text-" + i).toList();
        List<float[]> result = service.embedBatch(input);

        assertEquals(List.of(32, 32, 6), requestSizes);
        assertEquals(70, result.size());
        assertTrue(result.stream().allMatch(vector -> vector.length == 2));
    }

    @Test
    void retriesTransientBatchFailure() {
        BotAiModelConfigMapper mapper = mock(BotAiModelConfigMapper.class);
        RestTemplate rest = mock(RestTemplate.class);
        when(mapper.selectList(any())).thenReturn(List.of(model()));
        AtomicInteger calls = new AtomicInteger();
        when(rest.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
            .thenAnswer(invocation -> {
                if (calls.getAndIncrement() == 0) throw new ResourceAccessException("timeout");
                return ResponseEntity.ok(Map.of("data", List.of(
                    Map.of("index", 0, "embedding", List.of(0.1, 0.2)))));
            });

        EmbeddingService service = new EmbeddingService(mapper, rest, 32, 3, 0);
        List<float[]> result = service.embedBatch(List.of("test"));

        assertEquals(2, calls.get());
        assertEquals(2, result.get(0).length);
    }

    @Test
    void exposesStableModelDescriptorForIndexVersioning() {
        BotAiModelConfigMapper mapper = mock(BotAiModelConfigMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(model()));
        EmbeddingService service = new EmbeddingService(mapper, mock(RestTemplate.class), 32, 1, 0);

        EmbeddingService.EmbeddingDescriptor first = service.descriptor();
        EmbeddingService.EmbeddingDescriptor second = service.descriptor();

        assertEquals("embedding-3", first.model());
        assertEquals(first.version(), second.version());
        assertEquals(24, first.version().length());
    }

    private BotAiModelConfig model() {
        BotAiModelConfig model = new BotAiModelConfig();
        model.setModelName("embedding-3");
        model.setApiUrl("https://api.example.com/v1/embeddings");
        model.setApiKey("test-key");
        model.setIsDefault(1);
        model.setStatus(1);
        model.setModelType("Embedding");
        return model;
    }
}
