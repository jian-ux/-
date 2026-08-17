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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
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

    private final ThreadLocal<RerankDiagnostics> requestDiagnostics = new ThreadLocal<>();

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
        if (!enabled) {
            requestDiagnostics.set(RerankDiagnostics.notConfigured("disabled"));
            return false;
        }
        RerankConfig config = resolveConfig();
        if (config == null) {
            requestDiagnostics.set(RerankDiagnostics.notConfigured("missing_config"));
            return false;
        }
        requestDiagnostics.set(RerankDiagnostics.configured(config.configSource()));
        return true;
    }

    public Map<Integer, Double> rerank(String query, List<String> documents) {
        long started = System.nanoTime();
        if (!enabled) {
            setDiagnostics(RerankDiagnostics.notConfigured("disabled"), started);
            return Collections.emptyMap();
        }
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            setDiagnostics(RerankDiagnostics.notConfigured("invalid_input"), started);
            return Collections.emptyMap();
        }
        RerankConfig config = resolveConfig();
        if (config == null) {
            setDiagnostics(RerankDiagnostics.notConfigured("missing_config"), started);
            return Collections.emptyMap();
        }

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
            if (!response.getStatusCode().is2xxSuccessful()) {
                setDiagnostics(RerankDiagnostics.failed(config.configSource(), true,
                    "http_" + response.getStatusCode().value(), "fused"), started);
                return Collections.emptyMap();
            }
            Map<Integer, Double> scores = parseResults(response.getBody(), limited.size());
            if (scores.size() != limited.size()) {
                setDiagnostics(RerankDiagnostics.failed(config.configSource(), true,
                    scores.isEmpty() ? "invalid_response" : "partial_response", "fused"),
                    started);
                return scores;
            }
            setDiagnostics(RerankDiagnostics.applied(config.configSource(), true,
                "rerank"), started);
            return scores;
        } catch (HttpStatusCodeException e) {
            setDiagnostics(RerankDiagnostics.failed(config.configSource(), true,
                "http_" + e.getStatusCode().value(), "fused"), started);
            log.warn("Rerank request failed with HTTP status {}", e.getStatusCode().value());
            return Collections.emptyMap();
        } catch (ResourceAccessException e) {
            setDiagnostics(RerankDiagnostics.failed(config.configSource(), true,
                "connection_or_timeout", "fused"), started);
            log.warn("Rerank request was unreachable or timed out");
            return Collections.emptyMap();
        } catch (Exception e) {
            setDiagnostics(RerankDiagnostics.failed(config.configSource(), true,
                "request_failed", "fused"), started);
            log.warn("Rerank request failed; keeping fused retrieval order");
            return Collections.emptyMap();
        }
    }

    public RerankDiagnostics diagnostics() {
        return requestDiagnostics.get();
    }

    private RerankConfig resolveConfig() {
        try {
            for (Map<String, Object> model : adminClient.getActiveModels()) {
                String type = string(model.get("modelType"));
                if (!"Rerank".equalsIgnoreCase(type)) continue;
                String url = resolveRerankUrl(string(model.get("apiUrl")));
                String name = string(model.get("modelName"));
                if (!url.isBlank() && !name.isBlank()) {
                    return new RerankConfig(url, string(model.get("apiKey")), name,
                        "database");
                }
            }
        } catch (Exception e) {
            log.debug("Failed to load DB rerank config: {}", e.getMessage());
        }
        String url = resolveRerankUrl(fallbackUrl);
        if (url.isBlank() || fallbackModel == null || fallbackModel.isBlank()) return null;
        return new RerankConfig(url, fallbackApiKey, fallbackModel, "environment");
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

    private void setDiagnostics(RerankDiagnostics diagnostics, long started) {
        requestDiagnostics.set(diagnostics.withLatencyMs(elapsedMillis(started)));
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private record RerankConfig(String url, String apiKey, String model,
                                String configSource) {}

    public record RerankDiagnostics(
            boolean configured, boolean attempted, boolean applied,
            String failureReason, long latencyMs, String scoreSource,
            String configSource) {
        private static RerankDiagnostics notConfigured(String reason) {
            return new RerankDiagnostics(false, false, false, reason, 0,
                "none", null);
        }

        private static RerankDiagnostics configured(String source) {
            return new RerankDiagnostics(true, false, false, "not_attempted", 0,
                "fused", source);
        }

        private static RerankDiagnostics failed(String source, boolean attempted,
                                                String reason, String scoreSource) {
            return new RerankDiagnostics(true, attempted, false, reason, 0,
                scoreSource, source);
        }

        private static RerankDiagnostics applied(String source, boolean attempted,
                                                 String scoreSource) {
            return new RerankDiagnostics(true, attempted, true, null, 0,
                scoreSource, source);
        }

        private RerankDiagnostics withLatencyMs(long latency) {
            return new RerankDiagnostics(configured, attempted, applied, failureReason,
                latency, scoreSource, configSource);
        }

        public RerankDiagnostics withFailure(String reason, String source) {
            return new RerankDiagnostics(configured, attempted, false, reason,
                latencyMs, source, configSource);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("configured", configured);
            result.put("attempted", attempted);
            result.put("applied", applied);
            result.put("failureReason", failureReason);
            result.put("latencyMs", latencyMs);
            result.put("scoreSource", scoreSource);
            result.put("configSource", configSource);
            return Collections.unmodifiableMap(result);
        }
    }
}
