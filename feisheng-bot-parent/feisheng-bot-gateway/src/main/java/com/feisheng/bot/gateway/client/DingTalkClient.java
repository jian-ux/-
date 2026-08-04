package com.feisheng.bot.gateway.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.common.util.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 钉钉 API 客户端
 */
@Component
public class DingTalkClient {
    private static final String SEND_URL =
        "https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend";
    private static final String MESSAGE_FILE_URL =
        "https://api.dingtalk.com/v1.0/robot/messageFiles/download";
    private static final String ACCESS_TOKEN_HEADER = "x-acs-dingtalk-access-token";
    private static final int MAX_ERROR_DETAIL_CHARS = 180;
    private static final int MAX_TRANSIENT_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 250;
    private static final List<String> TRUSTED_MEDIA_HOST_SUFFIXES = List.of(
        ".dingtalk.com", ".aliyuncs.com", ".alicdn.com");

    private final RestTemplate rest;
    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    @Autowired
    public DingTalkClient(
            RedisUtil redisUtil,
            ObjectMapper objectMapper,
            @Value("${dingtalk.media.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${dingtalk.media.read-timeout-ms:30000}") int readTimeoutMs) {
        this(redisUtil, objectMapper,
            restTemplate(connectTimeoutMs, readTimeoutMs));
    }

    DingTalkClient(RedisUtil redisUtil, ObjectMapper objectMapper, RestTemplate rest) {
        this.redisUtil = redisUtil;
        this.objectMapper = objectMapper;
        this.rest = rest;
    }

    public String getAccessToken(String appKey, String appSecret) {
        String cacheKey = "dt:token:" + appKey + ":"
            + Integer.toUnsignedString(appSecret.hashCode(), 16);
        String cached = (String) redisUtil.get(cacheKey);
        if (cached != null) return cached;

        String url = "https://api.dingtalk.com/v1.0/oauth2/accessToken";
        Map<String, String> body = new HashMap<>();
        body.put("appKey", appKey);
        body.put("appSecret", appSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        Map<String, Object> resp = retryTransient(
            () -> rest.exchange(url, HttpMethod.POST, entity, Map.class).getBody());
        if (resp != null && resp.containsKey("accessToken")) {
            String token = (String) resp.get("accessToken");
            Object expireValue = resp.get("expireIn");
            int expiresIn = expireValue instanceof Number number ? number.intValue() : 7200;
            redisUtil.setex(cacheKey, token, expiresIn - 60, TimeUnit.SECONDS);
            return token;
        }
        throw new RuntimeException("Failed to get DingTalk accessToken: " + resp);
    }

    public boolean sendRobotMessage(String appKey, String appSecret, String robotCode,
                                     String userId, String content) {
        String token = getAccessToken(appKey, appSecret);

        Map<String, Object> body = new HashMap<>();
        body.put("robotCode", robotCode);
        body.put("userIds", List.of(userId));
        body.put("msgKey", "sampleText");
        body.put("msgParam", textMessageParam(content));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(ACCESS_TOKEN_HEADER, token);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            rest.exchange(SEND_URL, HttpMethod.POST, entity, Map.class);
            return true;
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException(formatApiError(e, "主动发送"), e);
        } catch (RestClientException e) {
            throw new IllegalStateException(
                "钉钉主动发送请求失败：" + truncate(e.getMessage()), e);
        }
    }

    public DownloadedMedia downloadRobotMessageFile(
            String appKey, String appSecret, String robotCode,
            String downloadCode, long maxBytes) {
        if (isBlank(appKey) || isBlank(appSecret) || isBlank(robotCode)
                || isBlank(downloadCode)) {
            throw new IllegalArgumentException(
                "appKey, appSecret, robotCode and downloadCode are required");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be greater than zero");
        }

        String token = getAccessToken(appKey.trim(), appSecret.trim());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(ACCESS_TOKEN_HEADER, token);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(Map.of(
            "downloadCode", downloadCode.trim(),
            "robotCode", robotCode.trim()), headers);

        try {
            Map<?, ?> response = retryTransient(() -> rest.exchange(
                MESSAGE_FILE_URL, HttpMethod.POST, entity, Map.class).getBody());
            String downloadUrl = response == null || response.get("downloadUrl") == null
                ? "" : response.get("downloadUrl").toString().trim();
            URI uri = validatedDownloadUri(downloadUrl);
            DownloadedMedia media = retryTransient(() ->
                rest.execute(uri, HttpMethod.GET, null,
                    clientResponse -> readBounded(clientResponse.getBody(),
                        clientResponse.getHeaders(), maxBytes)));
            if (media == null || media.content().length == 0) {
                throw new IllegalStateException("钉钉媒体文件内容为空");
            }
            return media;
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException(formatApiError(e, "媒体下载"), e);
        } catch (RestClientException e) {
            throw new IllegalStateException(
                "钉钉媒体下载请求失败：" + truncate(e.getMessage()), e);
        }
    }

    private String textMessageParam(String content) {
        try {
            return objectMapper.writeValueAsString(Map.of("content", content));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("钉钉文本消息序列化失败", e);
        }
    }

    private String formatApiError(HttpStatusCodeException error, String action) {
        StringBuilder message = new StringBuilder("钉钉").append(action).append("失败（HTTP ")
            .append(error.getStatusCode().value());
        String detail = error.getResponseBodyAsString();
        if (detail != null && !detail.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(detail);
                String code = root == null ? "" : root.path("code").asText("");
                String apiMessage = root == null ? "" : root.path("message").asText("");
                if (!code.isBlank()) message.append("，").append(code);
                detail = apiMessage.isBlank() ? detail : apiMessage;
            } catch (JsonProcessingException ignored) {
                // Preserve the upstream response text when it is not JSON.
            }
        }
        message.append("）");
        if (detail != null && !detail.isBlank()) {
            message.append("：").append(truncate(detail));
        }
        return message.toString();
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_ERROR_DETAIL_CHARS
            ? normalized : normalized.substring(0, MAX_ERROR_DETAIL_CHARS) + "...";
    }

    private URI validatedDownloadUri(String value) {
        try {
            URI uri = URI.create(value);
            if (isBlank(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) return uri;
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != -1
                    || !isTrustedMediaHost(uri.getHost())) {
                throw new IllegalArgumentException();
            }
            return URI.create("https://" + uri.toASCIIString().substring("http://".length()));
        } catch (Exception e) {
            throw new IllegalStateException("钉钉未返回可信的媒体下载地址", e);
        }
    }

    private <T> T retryTransient(Supplier<T> operation) {
        ResourceAccessException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            try {
                return operation.get();
            } catch (ResourceAccessException e) {
                lastFailure = e;
                if (attempt == MAX_TRANSIENT_ATTEMPTS) throw e;
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("钉钉网络请求重试被中断", interrupted);
                }
            }
        }
        throw lastFailure;
    }

    private boolean isTrustedMediaHost(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : TRUSTED_MEDIA_HOST_SUFFIXES) {
            if (normalized.endsWith(suffix)) return true;
        }
        return false;
    }

    private DownloadedMedia readBounded(InputStream input, HttpHeaders headers,
                                         long maxBytes) throws IOException {
        long declaredLength = headers.getContentLength();
        if (declaredLength > maxBytes) {
            throw new IllegalStateException("钉钉媒体文件超过大小限制：" + maxBytes + " 字节");
        }
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                 (int) Math.min(maxBytes, 64 * 1024))) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalStateException(
                        "钉钉媒体文件超过大小限制：" + maxBytes + " 字节");
                }
                output.write(buffer, 0, read);
            }
            MediaType type = headers.getContentType();
            String fileName = headers.getContentDisposition().getFilename();
            return new DownloadedMedia(output.toByteArray(),
                type == null ? "application/octet-stream" : type.toString(), fileName);
        }
    }

    private static RestTemplate restTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory =
            new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(1, connectTimeoutMs));
        requestFactory.setReadTimeout(Math.max(1, readTimeoutMs));
        return new RestTemplate(requestFactory);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record DownloadedMedia(byte[] content, String contentType, String fileName) {
    }
}
