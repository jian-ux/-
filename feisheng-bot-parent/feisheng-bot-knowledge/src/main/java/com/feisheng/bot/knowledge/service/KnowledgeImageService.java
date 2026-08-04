package com.feisheng.bot.knowledge.service;

import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeDocumentMapper;
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
import java.util.Optional;

@Service
public class KnowledgeImageService {
    private static final int STATUS_COMPLETED = 2;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final BotKnowledgeDocumentMapper documentMapper;
    private final MinioStorageService storageService;
    private final byte[] signingSecret;
    private final long urlTtlSeconds;
    private final long maxBytes;
    private final String publicBaseUrl;

    public KnowledgeImageService(
            BotKnowledgeDocumentMapper documentMapper,
            MinioStorageService storageService,
            @Value("${knowledge.images.signing-secret:${JWT_SECRET:change-this-secret}}")
            String signingSecret,
            @Value("${knowledge.images.url-ttl-seconds:3600}") long urlTtlSeconds,
            @Value("${knowledge.images.max-bytes:10485760}") long maxBytes,
            @Value("${app.public-base-url:}") String publicBaseUrl) {
        this.documentMapper = documentMapper;
        this.storageService = storageService;
        this.signingSecret = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.urlTtlSeconds = Math.max(60, urlTtlSeconds);
        this.maxBytes = Math.max(1, maxBytes);
        this.publicBaseUrl = normalizeBaseUrl(publicBaseUrl);
    }

    public Optional<ImageAttachment> attachment(Long documentId, String fallbackTitle) {
        BotKnowledgeDocument document = availableDocument(documentId);
        if (document == null) return Optional.empty();

        long expires = Instant.now().getEpochSecond() + urlTtlSeconds;
        String path = "/api/public/knowledge-images/" + documentId
            + "?expires=" + expires + "&signature=" + sign(documentId, expires);
        String title = firstNonBlank(document.getTitle(), document.getFileName(),
            fallbackTitle, "知识库图片");
        return Optional.of(new ImageAttachment(
            "image", documentId, title, publicBaseUrl + path));
    }

    public boolean verify(Long documentId, long expires, String signature) {
        if (documentId == null || signature == null || signature.isBlank()) return false;
        if (expires < Instant.now().getEpochSecond()) return false;
        byte[] expected = sign(documentId, expires).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    public ImageContent load(Long documentId) {
        BotKnowledgeDocument document = availableDocument(documentId);
        if (document == null) throw new ImageUnavailableException("Knowledge image is unavailable");
        if (document.getFileSize() != null && document.getFileSize() > maxBytes) {
            throw new ImageUnavailableException("Knowledge image exceeds the configured size limit");
        }
        try (InputStream input = storageService.download(document.getObjectKey())) {
            byte[] bytes = input.readNBytes((int) Math.min(Integer.MAX_VALUE, maxBytes + 1));
            if (bytes.length == 0 || bytes.length > maxBytes) {
                throw new ImageUnavailableException("Knowledge image content is empty or too large");
            }
            return new ImageContent(bytes, contentType(document),
                firstNonBlank(document.getFileName(), document.getTitle(), "image"));
        } catch (ImageUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageUnavailableException("Could not read knowledge image", e);
        }
    }

    private BotKnowledgeDocument availableDocument(Long documentId) {
        if (documentId == null) return null;
        BotKnowledgeDocument document = documentMapper.selectById(documentId);
        if (document == null
                || !"IMAGE".equalsIgnoreCase(document.getMediaType())
                || !"KNOWLEDGE".equalsIgnoreCase(document.getSourceScope())
                || document.getStatus() == null
                || document.getStatus() != STATUS_COMPLETED
                || document.getObjectKey() == null
                || document.getObjectKey().isBlank()) {
            return null;
        }
        return document;
    }

    private String sign(Long documentId, long expires) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingSecret, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal((documentId + ":" + expires)
                .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign knowledge image URL", e);
        }
    }

    private String contentType(BotKnowledgeDocument document) {
        String extension = firstNonBlank(document.getFileType(), extension(document.getFileName()))
            .toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    private static String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1);
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    public record ImageAttachment(String type, Long documentId, String title, String url) {}

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
