package com.feisheng.bot.core.service;

import com.feisheng.bot.core.client.AdminClient;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Embedding service: calls OpenAI-compatible text-embedding API.
 * P1-2: Tries DB-configured model first (via AdminClient), falls back to yml config.
 * This ensures admin and core use the same embedding model.
 */
@Service("coreEmbeddingService")
public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int PROVIDER_MAX_BATCH_SIZE = 64;
    private RestTemplate rest;
    private final AdminClient adminClient;

    @Value("${ai.embedding.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${ai.embedding.read-timeout-ms:30000}")
    private int readTimeoutMs;

    @Value("${ai.embedding.max-attempts:3}")
    private int maxAttempts = 3;

    @Value("${ai.embedding.retry-delay-ms:1000}")
    private long retryDelayMs = 1000;

    @Value("${ai.embedding.batch-size:32}")
    private int configuredBatchSize = 32;

    @Value("${ai.openai.embedding.url:https://api.openai.com/v1/embeddings}")
    private String fallbackApiUrl;

    @Value("${ai.openai.key:}")
    private String fallbackApiKey;

    @Value("${ai.openai.embedding.model:text-embedding-3-small}")
    private String fallbackModel;

    @Autowired
    public EmbeddingService(AdminClient adminClient) {
        this.adminClient = adminClient;
    }

    EmbeddingService(AdminClient adminClient, RestTemplate rest,
                     int maxAttempts, long retryDelayMs, int batchSize) {
        this.adminClient = adminClient;
        this.rest = rest;
        this.maxAttempts = maxAttempts;
        this.retryDelayMs = retryDelayMs;
        this.configuredBatchSize = batchSize;
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
        EmbeddingConfig config = resolveConfig();
        return config != null && config.apiKey != null && !config.apiKey.isEmpty();
    }

    public EmbeddingDescriptor descriptor() {
        EmbeddingConfig config = resolveConfig();
        if (config == null) return new EmbeddingDescriptor("", "");
        return new EmbeddingDescriptor(config.model,
            EmbeddingMetadataUtil.modelVersion(config.provider, config.model, config.apiUrl));
    }

    /**
     * Generate embedding for a single text.
     */
    public List<Double> embed(String text) {
        EmbeddingConfig config = resolveConfig();
        if (config == null || config.apiKey == null || config.apiKey.isEmpty()) {
            log.warn("Embedding API key not configured");
            return Collections.emptyList();
        }
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                return requestSingleEmbedding(config, text);
            } catch (Exception e) {
                log.warn("Embedding API attempt {}/{} failed: {}",
                    attempt, Math.max(1, maxAttempts), e.getMessage());
                if (!isRetryable(e) || attempt == Math.max(1, maxAttempts)
                        || !pauseBeforeRetry()) break;
            }
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<Double> requestSingleEmbedding(EmbeddingConfig config, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.model);
        body.put("input", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = rest.exchange(
            config.apiUrl, HttpMethod.POST, entity, Map.class);

        Map<String, Object> resp = response.getBody();
        if (resp != null && resp.containsKey("data")) {
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
            if (!data.isEmpty()) {
                List<Double> embedding = (List<Double>) data.get(0).get("embedding");
                if (embedding != null && !embedding.isEmpty()) return embedding;
            }
        }
        throw new IllegalStateException("Embedding API returned unexpected response");
    }

    /**
     * Batch embedding.
     */
    public Map<String, List<Double>> embedBatch(List<String> texts) {
        Map<String, List<Double>> result = new LinkedHashMap<>();
        if (texts == null || texts.isEmpty()) return result;
        EmbeddingConfig config = resolveConfig();
        if (config == null || config.apiKey == null || config.apiKey.isEmpty()) return result;

        int batchSize = Math.min(PROVIDER_MAX_BATCH_SIZE, Math.max(1, configuredBatchSize));
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(texts.size(), start + batchSize);
            List<String> batch = texts.subList(start, end);
            Map<String, List<Double>> batchResult = requestBatchWithRetry(config, batch,
                start / batchSize + 1);
            result.putAll(batchResult);
        }
        return result;
    }

    private Map<String, List<Double>> requestBatchWithRetry(EmbeddingConfig config,
                                                             List<String> texts,
                                                             int batchNumber) {
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                return requestBatch(config, texts);
            } catch (Exception e) {
                log.warn("Batch embedding {} attempt {}/{} failed: {}",
                    batchNumber, attempt, Math.max(1, maxAttempts), e.getMessage());
                if (!isRetryable(e) || attempt == Math.max(1, maxAttempts)
                        || !pauseBeforeRetry()) break;
            }
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Double>> requestBatch(EmbeddingConfig config, List<String> texts) {
        Map<String, List<Double>> result = new LinkedHashMap<>();
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.model);
        body.put("input", texts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = rest.exchange(
            config.apiUrl, HttpMethod.POST, entity, Map.class);

        Map<String, Object> resp = response.getBody();
        if (resp != null && resp.containsKey("data")) {
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
            for (Map<String, Object> item : data) {
                int index = ((Number) item.get("index")).intValue();
                List<Double> embedding = (List<Double>) item.get("embedding");
                if (index >= 0 && index < texts.size()
                        && embedding != null && !embedding.isEmpty()) {
                    result.put(texts.get(index), embedding);
                }
            }
            return result;
        }
        throw new IllegalStateException("Embedding API returned unexpected batch response");
    }

    private boolean isRetryable(Exception error) {
        if (error instanceof RestClientResponseException responseError) {
            return responseError.getStatusCode().is5xxServerError()
                || responseError.getStatusCode().value() == 429;
        }
        return true;
    }

    private boolean pauseBeforeRetry() {
        if (retryDelayMs <= 0) return true;
        try {
            Thread.sleep(retryDelayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---- P1-2: Resolve embedding config from DB first, fall back to yml ----

    private EmbeddingConfig resolveConfig() {
        // Try DB-configured models via AdminClient
        try {
            List<Map<String, Object>> models = adminClient.getActiveModels();
            if (models != null) {
                for (Map<String, Object> model : models) {
                    int status = numberValue(model.get("status"), 0);
                    String modelType = String.valueOf(model.getOrDefault("modelType", ""));
                    if (status == 1 && "Embedding".equalsIgnoreCase(modelType)) {
                        String apiUrl = stringValue(model.get("apiUrl"));
                        String apiKey = stringValue(model.get("apiKey"));
                        String modelName = stringValue(model.get("modelName"));
                        if (apiUrl != null && !apiUrl.isEmpty() && apiKey != null && !apiKey.isEmpty()) {
                            // Derive embedding URL from chat completions URL
                            String embedUrl = resolveEmbeddingUrl(apiUrl);
                            return new EmbeddingConfig(embedUrl, apiKey,
                                modelName != null ? modelName : fallbackModel,
                                stringValue(model.get("provider")));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get DB embedding config, falling back to yml: {}", e.getMessage());
        }

        // Fall back to yml config
        if (fallbackApiKey != null && !fallbackApiKey.isEmpty()) {
            return new EmbeddingConfig(fallbackApiUrl, fallbackApiKey, fallbackModel,
                "openai-compatible");
        }
        return null;
    }

    private static int numberValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String resolveEmbeddingUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (normalized.endsWith("/embeddings")) return normalized;
        if (normalized.endsWith("/chat/completions")) {
            return normalized.substring(0,
                normalized.length() - "/chat/completions".length()) + "/embeddings";
        }
        return normalized + "/embeddings";
    }

    private static class EmbeddingConfig {
        final String apiUrl;
        final String apiKey;
        final String model;
        final String provider;

        EmbeddingConfig(String apiUrl, String apiKey, String model, String provider) {
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.provider = provider;
        }
    }

    public record EmbeddingDescriptor(String model, String version) {}
}
