package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotAiModelConfig;
import com.feisheng.bot.admin.mapper.BotAiModelConfigMapper;
import com.feisheng.bot.common.util.EmbeddingMetadataUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;

@Service
public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int PROVIDER_MAX_BATCH_SIZE = 64;
    private final BotAiModelConfigMapper modelMapper;
    private RestTemplate rest;

    @Value("${ai.embedding.batch-size:32}")
    private int configuredBatchSize = 32;

    @Value("${ai.embedding.max-attempts:3}")
    private int maxAttempts = 3;

    @Value("${ai.embedding.retry-delay-ms:1000}")
    private long retryDelayMs = 1000;

    @Value("${ai.embedding.connect-timeout-ms:10000}")
    private int connectTimeoutMs = 10000;

    @Value("${ai.embedding.read-timeout-ms:30000}")
    private int readTimeoutMs = 30000;

    @Autowired
    public EmbeddingService(BotAiModelConfigMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    EmbeddingService(BotAiModelConfigMapper modelMapper, RestTemplate rest,
                     int batchSize, int maxAttempts, long retryDelayMs) {
        this.modelMapper = modelMapper;
        this.rest = rest;
        this.configuredBatchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryDelayMs = retryDelayMs;
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
        BotAiModelConfig model = findModel();
        return model != null
            && model.getApiUrl() != null && !model.getApiUrl().isBlank()
            && model.getApiKey() != null && !model.getApiKey().isBlank();
    }

    public EmbeddingDescriptor descriptor() {
        BotAiModelConfig model = findModel();
        if (model == null) return EmbeddingDescriptor.unavailable();
        String name = model.getModelName() == null ? "text-embedding-3-small" : model.getModelName();
        String provider = model.getProvider() == null ? "openai-compatible" : model.getProvider();
        return new EmbeddingDescriptor(name,
            EmbeddingMetadataUtil.modelVersion(provider, name, resolveEmbeddingUrl(model.getApiUrl())));
    }

    /** Generate embedding for a single text. Returns empty array on failure. */
    public float[] embed(String text) {
        if (text == null || text.isEmpty()) return new float[0];
        List<float[]> results = embedBatch(Collections.singletonList(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    /** Generate embeddings in provider-safe batches while preserving input order. */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return Collections.emptyList();
        List<float[]> results = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) results.add(new float[0]);

        BotAiModelConfig model = findModel();
        if (model == null) {
            log.warn("No enabled AI model configured for embedding generation");
            return results;
        }

        String baseUrl = model.getApiUrl();
        if (baseUrl == null || baseUrl.isEmpty()) return results;
        String embedUrl = resolveEmbeddingUrl(baseUrl);

        int batchSize = Math.min(PROVIDER_MAX_BATCH_SIZE, Math.max(1, configuredBatchSize));
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(texts.size(), start + batchSize);
            List<float[]> batch = embedBatchWithRetry(
                embedUrl, model, texts.subList(start, end), start / batchSize + 1);
            for (int i = 0; i < batch.size(); i++) {
                results.set(start + i, batch.get(i));
            }
        }

        long generated = results.stream().filter(value -> value.length > 0).count();
        int dimensions = results.stream().filter(value -> value.length > 0)
            .findFirst().map(value -> value.length).orElse(0);
        log.info("Generated {}/{} embeddings in batches of {}, dim={}",
            generated, texts.size(), batchSize, dimensions);
        return results;
    }

    private List<float[]> embedBatchWithRetry(String embedUrl, BotAiModelConfig model,
                                               List<String> texts, int batchNumber) {
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return requestBatch(embedUrl, model, texts);
            } catch (Exception e) {
                boolean retryable = isRetryable(e);
                log.warn("Embedding batch {} attempt {}/{} failed: {}",
                    batchNumber, attempt, attempts, e.getMessage());
                if (!retryable || attempt == attempts) break;
                if (!pauseBeforeRetry()) break;
            }
        }

        List<float[]> empty = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) empty.add(new float[0]);
        return empty;
    }

    @SuppressWarnings("unchecked")
    private List<float[]> requestBatch(String embedUrl, BotAiModelConfig model,
                                       List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.getModelName() != null
            ? model.getModelName() : "text-embedding-3-small");
        body.put("input", texts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(model.getApiKey());
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        Map<String, Object> response = rest.exchange(
            embedUrl, HttpMethod.POST, entity, Map.class).getBody();
        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) results.add(new float[0]);
        if (response == null || !(response.get("data") instanceof List<?> rawData)) {
            throw new IllegalStateException("Embedding API returned no data");
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) rawData;
        for (int position = 0; position < data.size(); position++) {
            Map<String, Object> item = data.get(position);
            int index = item.get("index") instanceof Number number
                ? number.intValue() : position;
            if (index < 0 || index >= results.size()
                    || !(item.get("embedding") instanceof List<?> rawEmbedding)) continue;
            float[] vector = new float[rawEmbedding.size()];
            boolean valid = true;
            for (int i = 0; i < rawEmbedding.size(); i++) {
                if (!(rawEmbedding.get(i) instanceof Number number)) {
                    valid = false;
                    break;
                }
                vector[i] = number.floatValue();
            }
            if (valid && vector.length > 0) results.set(index, vector);
        }
        return results;
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

    private BotAiModelConfig findModel() {
        List<BotAiModelConfig> models = modelMapper.selectList(
            new LambdaQueryWrapper<BotAiModelConfig>()
                .eq(BotAiModelConfig::getStatus, 1)
                .eq(BotAiModelConfig::getModelType, "Embedding")
                .orderByDesc(BotAiModelConfig::getIsDefault));
        if (models.isEmpty()) return null;
        for (BotAiModelConfig m : models) {
            if (m.getIsDefault() != null && m.getIsDefault() == 1) return m;
        }
        return models.get(0);
    }

    static String resolveEmbeddingUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (normalized.endsWith("/embeddings")) return normalized;
        if (normalized.endsWith("/chat/completions")) {
            return normalized.substring(0,
                normalized.length() - "/chat/completions".length()) + "/embeddings";
        }
        return normalized + "/embeddings";
    }

    public record EmbeddingDescriptor(String model, String version) {
        private static EmbeddingDescriptor unavailable() {
            return new EmbeddingDescriptor("", "");
        }
    }
}
