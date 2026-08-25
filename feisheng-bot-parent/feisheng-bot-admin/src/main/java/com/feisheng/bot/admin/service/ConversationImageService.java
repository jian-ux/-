package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotMessage;
import com.feisheng.bot.admin.mapper.BotMessageMapper;
import com.feisheng.bot.knowledge.service.MinioStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Service
public class ConversationImageService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final BotMessageMapper messageMapper;
    private final MinioStorageService storageService;
    private final ObjectMapper objectMapper;
    private final byte[] signingSecret;
    private final long urlTtlSeconds;
    private final long maxBytes;
    private final String publicBaseUrl;

    public ConversationImageService(
            BotMessageMapper messageMapper,
            MinioStorageService storageService,
            ObjectMapper objectMapper,
            @Value("${knowledge.images.signing-secret:${JWT_SECRET:change-this-secret}}")
            String signingSecret,
            @Value("${knowledge.images.url-ttl-seconds:3600}") long urlTtlSeconds,
            @Value("${knowledge.images.max-bytes:10485760}") long maxBytes,
            @Value("${app.public-base-url:}") String publicBaseUrl) {
        this.messageMapper = messageMapper;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.signingSecret = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.urlTtlSeconds = Math.max(60, urlTtlSeconds);
        this.maxBytes = Math.max(1, maxBytes);
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
    }

    public String url(BotMessage message) {
        if (!hasStoredImage(message)) return null;
        long expires = Instant.now().getEpochSecond() + urlTtlSeconds;
        String path = "/api/public/conversation-images/"
            + message.getConversationId() + "/" + message.getId()
            + "?expires=" + expires + "&signature="
            + sign(message.getConversationId(), message.getId(), expires);
        return publicBaseUrl + path;
    }

    public ImageContent load(Long conversationId, Long messageId,
                             long expires, String signature) {
        if (!verify(conversationId, messageId, expires, signature)) {
            throw new ImageUnavailableException("图片地址已失效");
        }
        BotMessage message = messageMapper.selectOne(new LambdaQueryWrapper<BotMessage>()
            .eq(BotMessage::getId, messageId)
            .eq(BotMessage::getConversationId, conversationId));
        if (!hasStoredImage(message)) {
            throw new ImageUnavailableException("图片不存在");
        }
        Map<String, Object> metadata = metadata(message);
        String objectKey = text(metadata.get("objectKey"));
        try (InputStream input = storageService.download(objectKey)) {
            byte[] bytes = input.readNBytes((int) Math.min(Integer.MAX_VALUE, maxBytes + 1));
            if (bytes.length == 0 || bytes.length > maxBytes) {
                throw new ImageUnavailableException("图片内容为空或超过大小限制");
            }
            String contentType = text(metadata.get("contentType"));
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                contentType = "image/jpeg";
            }
            return new ImageContent(bytes, contentType,
                firstText(metadata.get("fileName"), "image"));
        } catch (ImageUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageUnavailableException("无法读取图片", e);
        }
    }

    private boolean hasStoredImage(BotMessage message) {
        if (message == null || message.getId() == null
                || message.getConversationId() == null
                || !("image".equalsIgnoreCase(text(message.getContentType()))
                    || "mixed".equalsIgnoreCase(text(message.getContentType())))) {
            return false;
        }
        return !text(metadata(message).get("objectKey")).isBlank();
    }

    private Map<String, Object> metadata(BotMessage message) {
        if (message == null || message.getMetadata() == null
                || message.getMetadata().isBlank()) return Map.of();
        try {
            Map<String, Object> value = objectMapper.readValue(message.getMetadata(),
                new TypeReference<>() {});
            return value == null ? Map.of() : value;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean verify(Long conversationId, Long messageId,
                           long expires, String signature) {
        if (conversationId == null || messageId == null || signature == null
                || signature.isBlank() || expires < Instant.now().getEpochSecond()) {
            return false;
        }
        byte[] expected = sign(conversationId, messageId, expires)
            .getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private String sign(Long conversationId, Long messageId, long expires) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingSecret, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal((conversationId + ":" + messageId + ":" + expires)
                .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成图片签名", e);
        }
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String firstText(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public record ImageContent(byte[] bytes, String contentType, String fileName) {}

    public static class ImageUnavailableException extends RuntimeException {
        public ImageUnavailableException(String message) {
            super(message);
        }

        public ImageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
