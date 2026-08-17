package com.feisheng.bot.core.service;

import com.feisheng.bot.core.client.AdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankServiceTest {
    @Test
    void resolvesBaseUrlAndParsesCrossEncoderScores() {
        AdminClient adminClient = mock(AdminClient.class);
        RestTemplate rest = mock(RestTemplate.class);
        when(adminClient.getActiveModels()).thenReturn(List.of());
        when(rest.exchange(eq("https://rerank.example.com/v1/rerank"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(Map.class)))
            .thenReturn(ResponseEntity.ok(Map.of("results", List.of(
                Map.of("index", 1, "relevance_score", 0.91),
                Map.of("index", 0, "relevance_score", 0.22)))));
        RerankService service = new RerankService(adminClient, rest, true,
            "https://rerank.example.com/v1", "key", "bge-reranker");

        Map<Integer, Double> scores = service.rerank("怎么签合同", List.of("文档一", "文档二"));

        assertEquals(0.91, scores.get(1));
        assertEquals(0.22, scores.get(0));
        assertEquals(true, service.diagnostics().configured());
        assertEquals(true, service.diagnostics().attempted());
        assertEquals(true, service.diagnostics().applied());
        assertEquals(null, service.diagnostics().failureReason());
        assertEquals("rerank", service.diagnostics().scoreSource());
        assertEquals("environment", service.diagnostics().configSource());
        assertEquals(true, service.diagnostics().latencyMs() >= 0);
    }

    @Test
    void exposesFailureReasonWhenRerankerIsUnreachable() {
        AdminClient adminClient = mock(AdminClient.class);
        RestTemplate rest = mock(RestTemplate.class);
        when(adminClient.getActiveModels()).thenReturn(List.of());
        when(rest.exchange(eq("https://rerank.example.com/v1/rerank"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(Map.class)))
            .thenThrow(new ResourceAccessException("connection refused"));
        RerankService service = new RerankService(adminClient, rest, true,
            "https://rerank.example.com/v1", "key", "bge-reranker");

        assertEquals(Map.of(), service.rerank("怎么签合同", List.of("文档一")));
        assertEquals(true, service.diagnostics().configured());
        assertEquals(true, service.diagnostics().attempted());
        assertEquals(false, service.diagnostics().applied());
        assertEquals("connection_or_timeout", service.diagnostics().failureReason());
        assertEquals("fused", service.diagnostics().scoreSource());
    }

    @Test
    void convertsChatCompletionUrlToRerankEndpoint() {
        assertEquals("https://api.example.com/v1/rerank",
            RerankService.resolveRerankUrl("https://api.example.com/v1/chat/completions"));
    }
}
