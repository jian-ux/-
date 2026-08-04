package com.feisheng.bot.gateway.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public class WeChatWorkCryptoUtil {
    private static final int RANDOM_PREFIX_LENGTH = 16;
    private static final int PKCS7_BLOCK_SIZE = 32;

    private final byte[] aesKey;
    private final String token;
    private final String corpId;

    public WeChatWorkCryptoUtil(String encodingAesKey, String token, String corpId) {
        if (isBlank(encodingAesKey) || isBlank(token) || isBlank(corpId)) {
            throw new IllegalArgumentException("WeCom AES key, token and corpId are required");
        }
        this.token = token;
        this.corpId = corpId;
        String normalized = encodingAesKey;
        while (normalized.length() % 4 != 0) normalized += "=";
        this.aesKey = Base64.getDecoder().decode(normalized);
        if (this.aesKey.length != 32) {
            throw new IllegalArgumentException("WeCom AES key must decode to 32 bytes");
        }
    }

    public boolean verifySignature(String msgSignature, String timestamp, String nonce, String encrypted) {
        if (isBlank(msgSignature) || isBlank(timestamp) || isBlank(nonce) || isBlank(encrypted)) return false;
        byte[] expected = sha1(token, timestamp, nonce, encrypted).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, msgSignature.getBytes(StandardCharsets.UTF_8));
    }

    public String decryptEchoStr(String echoStr) {
        return decryptPayload(echoStr);
    }

    public String decryptMsg(String encrypted, String msgSignature, String timestamp, String nonce) {
        if (!verifySignature(msgSignature, timestamp, nonce, encrypted)) {
            throw new SecurityException("WeCom signature verification failed");
        }
        return decryptPayload(encrypted);
    }

    public String encryptReply(String replyXml, String timestamp, String nonce) {
        byte[] random = new byte[RANDOM_PREFIX_LENGTH];
        new SecureRandom().nextBytes(random);
        byte[] message = replyXml.getBytes(StandardCharsets.UTF_8);
        byte[] corpIdBytes = corpId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(random.length + Integer.BYTES + message.length + corpIdBytes.length);
        payload.put(random);
        payload.putInt(message.length);
        payload.put(message);
        payload.put(corpIdBytes);

        String encrypted = Base64.getEncoder().encodeToString(encrypt(payload.array()));
        String signature = sha1(token, timestamp, nonce, encrypted);
        return "<xml>"
            + "<Encrypt><![CDATA[" + encrypted + "]]></Encrypt>"
            + "<MsgSignature><![CDATA[" + signature + "]]></MsgSignature>"
            + "<TimeStamp>" + timestamp + "</TimeStamp>"
            + "<Nonce><![CDATA[" + escapeCdata(nonce) + "]]></Nonce>"
            + "</xml>";
    }

    private String decryptPayload(String encryptedText) {
        byte[] plain = decrypt(Base64.getDecoder().decode(encryptedText));
        if (plain.length < RANDOM_PREFIX_LENGTH + Integer.BYTES) {
            throw new IllegalArgumentException("WeCom decrypted payload is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(plain);
        buffer.position(RANDOM_PREFIX_LENGTH);
        int messageLength = buffer.getInt();
        if (messageLength < 0 || messageLength > buffer.remaining()) {
            throw new IllegalArgumentException("Invalid WeCom message length");
        }
        byte[] message = new byte[messageLength];
        buffer.get(message);
        byte[] receiveId = new byte[buffer.remaining()];
        buffer.get(receiveId);
        String actualCorpId = new String(receiveId, StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(corpId.getBytes(StandardCharsets.UTF_8), receiveId)) {
            throw new SecurityException("WeCom corpId does not match configured corpId: " + actualCorpId);
        }
        return new String(message, StandardCharsets.UTF_8);
    }

    private byte[] decrypt(byte[] encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new IvParameterSpec(aesKey, 0, 16));
            return pkcs7Unpad(cipher.doFinal(encrypted));
        } catch (SecurityException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("WeCom AES decrypt failed", e);
        }
    }

    private byte[] encrypt(byte[] plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new IvParameterSpec(aesKey, 0, 16));
            return cipher.doFinal(pkcs7Pad(plain));
        } catch (Exception e) {
            throw new IllegalStateException("WeCom AES encrypt failed", e);
        }
    }

    private static byte[] pkcs7Pad(byte[] plain) {
        int padding = PKCS7_BLOCK_SIZE - plain.length % PKCS7_BLOCK_SIZE;
        byte[] result = Arrays.copyOf(plain, plain.length + padding);
        Arrays.fill(result, plain.length, result.length, (byte) padding);
        return result;
    }

    private static byte[] pkcs7Unpad(byte[] plain) {
        if (plain.length == 0) throw new IllegalArgumentException("Empty WeCom encrypted payload");
        int padding = plain[plain.length - 1] & 0xff;
        if (padding < 1 || padding > PKCS7_BLOCK_SIZE || padding > plain.length) {
            throw new IllegalArgumentException("Invalid WeCom PKCS7 padding");
        }
        for (int i = plain.length - padding; i < plain.length; i++) {
            if ((plain[i] & 0xff) != padding) {
                throw new IllegalArgumentException("Invalid WeCom PKCS7 padding");
            }
        }
        return Arrays.copyOf(plain, plain.length - padding);
    }

    private static String sha1(String... values) {
        try {
            Arrays.sort(values);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(String.join("", values).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("WeCom SHA-1 failed", e);
        }
    }

    private static String escapeCdata(String value) {
        return value == null ? "" : value.replace("]]>", "]]]]><![CDATA[>");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
