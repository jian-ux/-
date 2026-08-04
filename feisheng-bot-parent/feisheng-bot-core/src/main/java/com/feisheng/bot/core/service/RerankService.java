package com.feisheng.bot.core.service;

import com.feisheng.bot.core.client.AdminClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional Cross-Encoder client using the common query/documents rerank contract. */
@Service
public class RerankService {
    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final AdminClient adminClient;
    private RestTemplate rest;

    @Value("${rag.rerank.enabled:false}")
    private boolean enabled;

    @Value("${rag.rerank.url:}")
    private String fallbackUrl;

    @Value("${rag.rerank.api-key:}")
    private String fallbackApiKey;

    @Value("${rag.rerank.model:}")
    private String fallbackModel;

    @Value("${rag.rerank.connect-timeout-ms:1000}")
    private int connectTimeoutMs;

    @Value("${rag.rerank.read-timeout-ms:3000}")
    private int readTimeoutMs;

    @Value("${rag.rerank.max-candidates:10}")
    private int maxCandidates = 10;

    @Value("${rag.rerank.max-document-chars:2000}")
    private int maxDocumentChars = 2000;

    @Autowired
    public RerankService(AdminClient adminClient) {
        this.adminClient = adminClient;
    }

    RerankService(AdminClient adminClient, RestTemplate rest, boolean enabled,
                  String fallbackUrl, String fallbackApiKey, String fallbackModel) {
        this.adminClient = adminClient;
        this.rest = rest;
        this.enabled = enabled;
        this.fallbackUrl = fallbackUrl;
        this.fallbackApiKey = fallbackApiKey;
        this.fallbackModel = fallbackModel;
    }

    @PostConstruct
    public void init() {
        if (rest != null) return;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        rest = new RestTemplate(factory);
    }

    public boolean isAvailable() {
        return enabled && resolveConfig() != null;
    }

    public Map<Integer, Double> rerank(String query, List<String> documents) {
        if (!enabled || query == null || query.isBlank()
                || documents == null || documents.isEmpty()) return Collections.emptyMap();
        RerankConfig config = resolveConfig();
        if (config == null) return Collections.emptyMap();

        List<String> limited = documents.stream()
            .limit(Math.max(1, maxCandidates))
            .map(value -> truncate(value, Math.max(100, maxDocumentChars)))
            .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("query", query);
        body.put("documents", limited);
        body.put("top_n", limited.size());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            headers.setBearerAuth(config.apiKey());
        }
        try {
            ResponseEntity<Map> response = rest.exchange(config.url(), HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
            return parseResults(response.getBody(), limited.size());
        } catch (Exception e) {
            log.warn("Rerank request failed; keeping fused retrieval order: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private RerankConfig resolveConfig() {
        try {
            for (Map<String, Object> model : adminClient.getActiveModels()) {
                String type = string(model.get("modelType"));
                if (!"Rerank".equalsIgnoreCase(type)) continue;
                String url = resolveRerankUrl(string(model.get("apiUrl")));
                String name = string(model.get("modelName"));
                if (!url.isBlank() && !name.isBlank()) {
                    return new RerankConfig(url, string(model.get("apiKey")), name);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to load DB rerank config: {}", e.getMessage());
        }
        String url = resolveRerankUrl(fallbackUrl);
        if (url.isBlank() || fallbackModel == null || fallbackModel.isBlank()) return null;
        return new RerankConfig(url, fallbackApiKey, fallbackModel);
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, Double> parseResults(Map<?, ?> response, int documentCount) {
        if (response == null) return Collections.emptyMap();
        Object rawResults = response.get("results");
        if (!(rawResults instanceof List<?>)) rawResults = response.get("data");
        if (!(rawResults instanceof List<?> results)) return Collections.emptyMap();

        Map<Integer, Double> scores = new LinkedHashMap<>();
        for (Object value : results) {
            if (!(value instanceof Map<?, ?> result)) continue;
            Object indexValue = result.get("index");
            Object scoreValue = result.containsKey("relevance_score")
                ? result.get("relevance_score") : result.get("score");
            if (!(indexValue instanceof Number index) || !(scoreValue instanceof Number score)) continue;
            int position = index.intValue();
            if (position >= 0 && position < documentCount) {
                scores.put(position, score.doubleValue());
            }
        }
        return Map.copyOf(scores);
    }

    static String resolveRerankUrl(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("/+$", "");
        if (normalized.isBlank() || normalized.endsWith("/rerank")) return normalized;
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0,
                normalized.length() - "/chat/completions".length());
        }
        return normalized + "/rerank";
    }

    private String truncate(String value, int maxLength) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private record RerankConfig(String url, String apiKey, String model) {}
}
