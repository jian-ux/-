package com.feisheng.bot.gateway.util;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class DingTalkCryptoUtil {
    private static final int RANDOM_PREFIX_LENGTH = 16;
    private static final int PKCS7_BLOCK_SIZE = 32;

    private DingTalkCryptoUtil() {}

    public static boolean verifySignature(String timestamp, String sign, String appSecret) {
        if (isBlank(timestamp) || isBlank(sign) || isBlank(appSecret)) return false;
        try {
            byte[] expected = computeSignature(timestamp, appSecret).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, sign.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    public static String computeSignature(String timestamp, String appSecret) throws Exception {
        if (isBlank(timestamp) || isBlank(appSecret)) {
            throw new IllegalArgumentException("timestamp and appSecret are required");
        }
        String stringToSign = timestamp + "\n" + appSecret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String base64Sign = Base64.getEncoder().encodeToString(
            mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        return URLEncoder.encode(base64Sign, StandardCharsets.UTF_8);
    }

    public static boolean verifyEnterpriseSignature(String token, String timestamp, String nonce,
                                                     String encrypt, String signature) {
        if (isBlank(token) || isBlank(timestamp) || isBlank(nonce)
                || isBlank(encrypt) || isBlank(signature)) return false;
        try {
            byte[] expected = computeEnterpriseSignature(token, timestamp, nonce, encrypt)
                .getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    public static DecryptedPayload decryptPayload(String encryptedText, String encodingAesKey) throws Exception {
        byte[] aesKey = decodeAesKey(encodingAesKey);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
            new IvParameterSpec(aesKey, 0, 16));
        byte[] plain = pkcs7Unpad(cipher.doFinal(Base64.getDecoder().decode(encryptedText)));
        if (plain.length < RANDOM_PREFIX_LENGTH + Integer.BYTES) {
            throw new IllegalArgumentException("Decrypted payload is too short");
        }

        ByteBuffer buffer = ByteBuffer.wrap(plain);
        buffer.position(RANDOM_PREFIX_LENGTH);
        int messageLength = buffer.getInt();
        if (messageLength < 0 || messageLength > buffer.remaining()) {
            throw new IllegalArgumentException("Invalid decrypted message length");
        }
        byte[] message = new byte[messageLength];
        buffer.get(message);
        byte[] receiveId = new byte[buffer.remaining()];
        buffer.get(receiveId);
        return new DecryptedPayload(
            new String(message, StandardCharsets.UTF_8),
            new String(receiveId, StandardCharsets.UTF_8));
    }

    public static String encrypt(String text, String receiveId, String encodingAesKey) throws Exception {
        byte[] aesKey = decodeAesKey(encodingAesKey);
        byte[] random = new byte[RANDOM_PREFIX_LENGTH];
        new SecureRandom().nextBytes(random);
        byte[] message = text.getBytes(StandardCharsets.UTF_8);
        byte[] id = receiveId == null ? new byte[0] : receiveId.getBytes(StandardCharsets.UTF_8);

        ByteBuffer payload = ByteBuffer.allocate(random.length + Integer.BYTES + message.length + id.length);
        payload.put(random);
        payload.putInt(message.length);
        payload.put(message);
        payload.put(id);

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
            new IvParameterSpec(aesKey, 0, 16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(pkcs7Pad(payload.array())));
    }

    public static String computeEnterpriseSignature(String token, String timestamp, String nonce,
                                                    String encrypt) throws Exception {
        String[] values = {token, timestamp, nonce, encrypt};
        if (Arrays.stream(values).anyMatch(DingTalkCryptoUtil::isBlank)) {
            throw new IllegalArgumentException("Enterprise signature fields are required");
        }
        Arrays.sort(values);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(String.join("", values).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte b : hash) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }

    private static byte[] decodeAesKey(String encodingAesKey) {
        if (isBlank(encodingAesKey)) throw new IllegalArgumentException("DingTalk AES key is required");
        String normalized = encodingAesKey;
        while (normalized.length() % 4 != 0) normalized += "=";
        byte[] key = Base64.getDecoder().decode(normalized);
        if (key.length != 32) throw new IllegalArgumentException("DingTalk AES key must decode to 32 bytes");
        return key;
    }

    private static byte[] pkcs7Pad(byte[] data) {
        int padding = PKCS7_BLOCK_SIZE - data.length % PKCS7_BLOCK_SIZE;
        byte[] result = Arrays.copyOf(data, data.length + padding);
        Arrays.fill(result, data.length, result.length, (byte) padding);
        return result;
    }

    private static byte[] pkcs7Unpad(byte[] data) {
        if (data.length == 0) throw new IllegalArgumentException("Empty encrypted payload");
        int padding = data[data.length - 1] & 0xff;
        if (padding < 1 || padding > PKCS7_BLOCK_SIZE || padding > data.length) {
            throw new IllegalArgumentException("Invalid PKCS7 padding");
        }
        for (int i = data.length - padding; i < data.length; i++) {
            if ((data[i] & 0xff) != padding) throw new IllegalArgumentException("Invalid PKCS7 padding");
        }
        return Arrays.copyOf(data, data.length - padding);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record DecryptedPayload(String message, String receiveId) {}
}
