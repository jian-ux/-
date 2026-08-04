package com.feisheng.bot.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class EmbeddingMetadataUtil {
    private EmbeddingMetadataUtil() {}

    public static String modelVersion(String provider, String model, String apiUrl) {
        return sha256(String.join("|", safe(provider), safe(model), normalizeUrl(apiUrl))).substring(0, 24);
    }

    public static String contentHash(String content) {
        return sha256(safe(content));
    }

    private static String normalizeUrl(String value) {
        return safe(value).trim().replaceAll("/+$", "").toLowerCase();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) result.append(String.format("%02x", valueByte));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
