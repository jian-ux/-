package com.feisheng.bot.knowledge.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal Qdrant REST client for the knowledge index.
 */
@Component
public class QdrantVectorStore {
    private final RestTemplate restTemplate;
    private final boolean enabled;
    private final String baseUrl;
    private final String collection;
    private final int vectorSize;
    private final int batchSize;

    private volatile QdrantStatus lastKnownStatus;

    @Autowired
    public QdrantVectorStore(
            RestTemplateBuilder builder,
            @Value("${qdrant.enabled:true}") boolean enabled,
            @Value("${qdrant.url:http://localhost:6333}") String baseUrl,
            @Value("${qdrant.api-key:}") String apiKey,
            @Value("${qdrant.collection:feisheng_knowledge}") String collection,
            @Value("${qdrant.vector-size:2048}") int vectorSize,
            @Value("${qdrant.batch-size:64}") int batchSize,
            @Value("${qdrant.connect-timeout-seconds:2}") int connectTimeoutSeconds,
            @Value("${qdrant.read-timeout-seconds:10}") int readTimeoutSeconds) {
        this(buildRestTemplate(builder, apiKey, connectTimeoutSeconds, readTimeoutSeconds),
            enabled, baseUrl, collection, vectorSize, batchSize);
    }

    QdrantVectorStore(RestTemplate restTemplate, boolean enabled, String baseUrl,
                      String collection, int vectorSize, int batchSize) {
        this.restTemplate = restTemplate;
        this.enabled = enabled;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.collection = collection;
        this.vectorSize = vectorSize;
        this.batchSize = Math.max(1, batchSize);
        this.lastKnownStatus = new QdrantStatus(
            enabled, false, this.baseUrl, collection, vectorSize,
            null, null, null, null, Instant.now().toString(),
            enabled ? "Qdrant has not been checked yet" : null);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int vectorSize() {
        return vectorSize;
    }

    /** Creates an isolated client using the same transport and vector settings. */
    public QdrantVectorStore forCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalArgumentException("Qdrant collection name cannot be blank");
        }
        String normalized = collectionName.trim();
        if (collection.equals(normalized)) {
            throw new IllegalArgumentException(
                "Independent Qdrant index must use a different collection");
        }
        return new QdrantVectorStore(restTemplate, enabled, baseUrl,
            normalized, vectorSize, batchSize);
    }

    public ReconcileResult reconcile(List<VectorPoint> points) {
        requireEnabled();
        ensureCollection();

        Set<String> remoteIds = scrollPointIds();
        Set<String> expectedIds = new HashSet<>();
        for (VectorPoint point : points) {
            expectedIds.add(point.id());
        }

        remoteIds.removeAll(expectedIds);
        int deleted = delete(remoteIds);
        int upserted = upsert(points);
        markAvailable(null, null, null, "Cosine");
        return new ReconcileResult(upserted, deleted);
    }

    public ReconcileResult applyChanges(List<VectorPoint> upserts, Collection<String> deletedIds,
                                        List<VectorPoint> fullSnapshot) {
        requireEnabled();
        CollectionDetails before = ensureCollection();
        int deleted = delete(deletedIds);
        int upserted = upsert(upserts);
        CollectionDetails after = upserts.isEmpty() && (deletedIds == null || deletedIds.isEmpty())
            ? before : collectionDetails();
        if (after.pointsCount() == null || after.pointsCount() != fullSnapshot.size()) {
            return reconcile(fullSnapshot);
        }
        markAvailable(after.pointsCount(), after.indexedVectorsCount(),
            after.vectorSize(), after.distance());
        return new ReconcileResult(upserted, deleted);
    }

    public List<SearchHit> search(List<Double> vector, int limit, double minScore) {
        return search(vector, limit, minScore, Collections.emptyMap());
    }

    public List<SearchHit> search(List<Double> vector, int limit, double minScore,
                                  Map<String, Object> filters) {
        requireEnabled();
        validateVector(vector);
        Map<String, Object> normalizedFilters = PayloadFilters.normalize(filters);
        if (PayloadFilters.isUnsatisfiable(normalizedFilters)) return Collections.emptyList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", vector);
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("with_vector", false);
        if (minScore > -1) {
            body.put("score_threshold", minScore);
        }
        if (!normalizedFilters.isEmpty()) {
            body.put("filter", PayloadFilters.toQdrantFilter(normalizedFilters));
        }

        Map<String, Object> response = request(HttpMethod.POST, pointsPath("search"), body);
        List<?> values = response.get("result") instanceof List<?> list ? list : Collections.emptyList();
        List<SearchHit> hits = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> result = stringKeyMap(raw);
            Map<String, Object> payload = result.get("payload") instanceof Map<?, ?> payloadMap
                ? stringKeyMap(payloadMap) : Collections.emptyMap();
            hits.add(new SearchHit(payload, number(result.get("score"))));
        }
        markAvailable(null, null, null, "Cosine");
        return hits;
    }

    public QdrantStatus status() {
        if (!enabled) return lastKnownStatus;
        try {
            CollectionDetails details = collectionDetails();
            validateCollection(details);
            markAvailable(details.pointsCount(), details.indexedVectorsCount(),
                details.vectorSize(), details.distance());
        } catch (Exception e) {
            markUnavailable(rootMessage(e));
        }
        return lastKnownStatus;
    }

    public QdrantStatus lastKnownStatus() {
        return lastKnownStatus;
    }

    public static String pointId(String sourceKey) {
        return UUID.nameUUIDFromBytes(
            ("feisheng-bot:" + sourceKey).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private CollectionDetails ensureCollection() {
        CollectionDetails details;
        try {
            details = collectionDetails();
        } catch (HttpClientErrorException.NotFound e) {
            Map<String, Object> vectors = new LinkedHashMap<>();
            vectors.put("size", vectorSize);
            vectors.put("distance", "Cosine");
            request(HttpMethod.PUT, collectionPath(), Map.of("vectors", vectors));
            details = collectionDetails();
        }
        validateCollection(details);
        return details;
    }

    private CollectionDetails collectionDetails() {
        Map<String, Object> response = request(HttpMethod.GET, collectionPath(), null);
        Map<String, Object> result = map(response.get("result"));
        Map<String, Object> config = map(result.get("config"));
        Map<String, Object> params = map(config.get("params"));
        Map<String, Object> vectors = map(params.get("vectors"));
        return new CollectionDetails(
            longNumber(result.get("points_count")),
            longNumber(result.get("indexed_vectors_count")),
            integer(vectors.get("size")),
            Objects.toString(vectors.get("distance"), null));
    }

    private void validateCollection(CollectionDetails details) {
        if (details.vectorSize() == null) {
            throw new IllegalStateException("Qdrant collection vector size is missing");
        }
        if (details.vectorSize() != vectorSize) {
            throw new IllegalStateException("Qdrant collection vector size " + details.vectorSize()
                + " does not match configured size " + vectorSize);
        }
        if (!"cosine".equalsIgnoreCase(details.distance())) {
            throw new IllegalStateException("Qdrant collection distance must be Cosine, but was "
                + details.distance());
        }
    }

    private Set<String> scrollPointIds() {
        Set<String> ids = new HashSet<>();
        Object offset = null;
        do {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("limit", Math.max(batchSize, 256));
            body.put("with_payload", false);
            body.put("with_vector", false);
            if (offset != null) body.put("offset", offset);

            Map<String, Object> response = request(HttpMethod.POST, pointsPath("scroll"), body);
            Map<String, Object> result = map(response.get("result"));
            List<?> points = result.get("points") instanceof List<?> list ? list : Collections.emptyList();
            for (Object point : points) {
                if (point instanceof Map<?, ?> raw && raw.get("id") != null) {
                    ids.add(raw.get("id").toString());
                }
            }
            Object nextOffset = result.get("next_page_offset");
            if (nextOffset == null || Objects.equals(nextOffset, offset)) break;
            offset = nextOffset;
        } while (true);
        return ids;
    }

    private int upsert(List<VectorPoint> points) {
        int total = 0;
        for (int start = 0; start < points.size(); start += batchSize) {
            int end = Math.min(start + batchSize, points.size());
            List<Map<String, Object>> payload = new ArrayList<>(end - start);
            for (VectorPoint point : points.subList(start, end)) {
                validateVector(point.vector());
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", point.id());
                value.put("vector", point.vector());
                value.put("payload", point.payload());
                payload.add(value);
            }
            request(HttpMethod.PUT, pointsPath("") + "?wait=true", Map.of("points", payload));
            total += payload.size();
        }
        return total;
    }

    private int delete(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        List<String> values = new ArrayList<>(ids);
        int total = 0;
        for (int start = 0; start < values.size(); start += batchSize) {
            int end = Math.min(start + batchSize, values.size());
            List<String> batch = values.subList(start, end);
            request(HttpMethod.POST, pointsPath("delete") + "?wait=true", Map.of("points", batch));
            total += batch.size();
        }
        return total;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> request(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<?> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(baseUrl + path, method, entity, Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Qdrant returned " + response.getStatusCode());
            }
            return (Map<String, Object>) response.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            throw e;
        } catch (RestClientException e) {
            throw new IllegalStateException("Qdrant request failed: " + rootMessage(e), e);
        }
    }

    private String collectionPath() {
        return "/collections/" + UriUtils.encodePathSegment(collection, StandardCharsets.UTF_8);
    }

    private String pointsPath(String operation) {
        String path = collectionPath() + "/points";
        return operation == null || operation.isBlank() ? path : path + "/" + operation;
    }

    private void validateVector(List<Double> vector) {
        if (vector == null || vector.size() != vectorSize) {
            throw new IllegalArgumentException("Vector dimension must be " + vectorSize + ", but was "
                + (vector == null ? 0 : vector.size()));
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("Qdrant is disabled");
    }

    private void markAvailable(Long pointsCount, Long indexedVectorsCount,
                               Integer actualVectorSize, String distance) {
        lastKnownStatus = new QdrantStatus(
            true, true, baseUrl, collection, vectorSize,
            pointsCount, indexedVectorsCount,
            actualVectorSize == null ? vectorSize : actualVectorSize,
            distance, Instant.now().toString(), null);
    }

    private void markUnavailable(String error) {
        QdrantStatus previous = lastKnownStatus;
        lastKnownStatus = new QdrantStatus(
            enabled, false, baseUrl, collection, vectorSize,
            previous.pointsCount(), previous.indexedVectorsCount(),
            previous.collectionVectorSize(), previous.distance(),
            Instant.now().toString(), error);
    }

    private static RestTemplate buildRestTemplate(RestTemplateBuilder builder, String apiKey,
                                                  int connectTimeoutSeconds, int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(1, connectTimeoutSeconds) * 1000);
        requestFactory.setReadTimeout(Math.max(1, readTimeoutSeconds) * 1000);
        RestTemplateBuilder configured = builder.requestFactory(() -> requestFactory);
        if (apiKey != null && !apiKey.isBlank()) {
            configured = configured.defaultHeader("api-key", apiKey);
        }
        return configured.build();
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? stringKeyMap(raw) : Collections.emptyMap();
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static Long longNumber(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record VectorPoint(String id, List<Double> vector, Map<String, Object> payload) {}

    public record SearchHit(Map<String, Object> payload, double score) {}

    public record ReconcileResult(int upserted, int deleted) {}

    public record QdrantStatus(boolean enabled, boolean available, String url, String collection,
                               int configuredVectorSize, Long pointsCount, Long indexedVectorsCount,
                               Integer collectionVectorSize, String distance,
                               String checkedAt, String error) {}

    private record CollectionDetails(Long pointsCount, Long indexedVectorsCount,
                                     Integer vectorSize, String distance) {}
}
