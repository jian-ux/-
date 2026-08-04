package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.service.BusinessDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;
import java.util.Objects;

@Service
@ConditionalOnProperty(prefix = "business.api", name = "enabled", havingValue = "true")
public class HttpBusinessDataProvider implements BusinessDataProvider {
    private static final Logger log = LoggerFactory.getLogger(HttpBusinessDataProvider.class);
    private static final DateTimeFormatter SQL_DATE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiToken;
    private final String orderPath;
    private final String logisticsPath;
    private final boolean requireOwnerMatch;

    public HttpBusinessDataProvider(
            RestTemplateBuilder builder,
            @Value("${business.api.base-url:}") String baseUrl,
            @Value("${business.api.token:}") String apiToken,
            @Value("${business.api.order-path:/orders/{orderNo}}") String orderPath,
            @Value("${business.api.logistics-path:/logistics/{orderNo}}") String logisticsPath,
            @Value("${business.api.connect-timeout-seconds:3}") int connectTimeoutSeconds,
            @Value("${business.api.read-timeout-seconds:8}") int readTimeoutSeconds,
            @Value("${business.api.require-owner-match:true}") boolean requireOwnerMatch) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
            .setReadTimeout(Duration.ofSeconds(Math.max(1, readTimeoutSeconds)))
            .build();
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiToken = apiToken;
        this.orderPath = orderPath;
        this.logisticsPath = logisticsPath;
        this.requireOwnerMatch = requireOwnerMatch;
    }

    @Override
    public String providerCode() {
        return "http";
    }

    @Override
    public boolean available() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    @Override
    public QueryResult<OrderView> findOrder(QueryIdentity identity, String orderNo,
                                            String requestId) {
        if (!available()) return QueryResult.unavailable("业务 API 未配置");
        QueryResult<Map<String, Object>> raw = get(orderPath, orderNo, identity, requestId);
        if (raw.status() != QueryStatus.FOUND) return copyStatus(raw);
        Map<String, Object> data = raw.data();
        if (!ownerMatches(data, identity)) return QueryResult.forbidden();
        return QueryResult.found(new OrderView(
            string(data, "orderNo", orderNo), string(data, "status", null),
            string(data, "paymentStatus", null), string(data, "itemSummary", null),
            longValue(data.get("amountCents")), string(data, "currency", "CNY"),
            dateValue(data.get("orderTime"))));
    }

    @Override
    public QueryResult<LogisticsView> findLogistics(QueryIdentity identity, String orderNo,
                                                    String requestId) {
        QueryResult<OrderView> ownership = findOrder(identity, orderNo, requestId + "-owner");
        if (ownership.status() != QueryStatus.FOUND) return copyStatus(ownership);
        QueryResult<Map<String, Object>> raw = get(logisticsPath, orderNo, identity, requestId);
        if (raw.status() != QueryStatus.FOUND) return copyStatus(raw);
        Map<String, Object> data = raw.data();
        return QueryResult.found(new LogisticsView(
            string(data, "orderNo", orderNo), string(data, "carrier", null),
            string(data, "trackingNo", null), string(data, "status", null),
            string(data, "latestEvent", null), dateValue(data.get("latestEventTime")),
            dateValue(data.get("estimatedDeliveryTime"))));
    }

    private QueryResult<Map<String, Object>> get(String path, String orderNo,
                                                  QueryIdentity identity, String requestId) {
        try {
            String url = UriComponentsBuilder.fromUriString(baseUrl)
                .path(path).buildAndExpand(orderNo).encode().toUriString();
            HttpHeaders headers = new HttpHeaders();
            if (apiToken != null && !apiToken.isBlank()) headers.setBearerAuth(apiToken);
            headers.set("X-Channel-Type", identity.channelType());
            headers.set("X-Channel-User-Id", identity.channelUserId());
            headers.set("X-Request-Id", requestId);
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = response.getBody();
            if (body == null) return QueryResult.error("业务 API 返回空响应");
            Object nested = body.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = nested instanceof Map<?, ?>
                ? (Map<String, Object>) nested : body;
            return QueryResult.found(data);
        } catch (HttpClientErrorException.NotFound e) {
            return QueryResult.notFound();
        } catch (HttpClientErrorException.Forbidden e) {
            return QueryResult.forbidden();
        } catch (Exception e) {
            log.warn("Business API request failed, requestId={}, type={}",
                requestId, e.getClass().getSimpleName());
            return QueryResult.unavailable("业务 API 请求失败");
        }
    }

    private boolean ownerMatches(Map<String, Object> data, QueryIdentity identity) {
        if (!requireOwnerMatch) return true;
        return Objects.equals(string(data, "channelType", null), identity.channelType())
            && Objects.equals(string(data, "channelUserId", null), identity.channelUserId());
    }

    private <T> QueryResult<T> copyStatus(QueryResult<?> source) {
        return new QueryResult<>(source.status(), null, source.message());
    }

    private String string(Map<String, Object> data, String key, String fallback) {
        Object value = data == null ? null : data.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Date dateValue(Object value) {
        if (value instanceof Date date) return date;
        if (value instanceof Number number) return new Date(number.longValue());
        if (value == null || value.toString().isBlank()) return null;
        String text = value.toString().trim();
        try {
            return Date.from(Instant.parse(text));
        } catch (Exception ignored) {
            try {
                LocalDateTime local = LocalDateTime.parse(text, SQL_DATE_TIME);
                return Date.from(local.atZone(ZoneId.systemDefault()).toInstant());
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null) return "";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    RestTemplate restTemplate() {
        return restTemplate;
    }
}
