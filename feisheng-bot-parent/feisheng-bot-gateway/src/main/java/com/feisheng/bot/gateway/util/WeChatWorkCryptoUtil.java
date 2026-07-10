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

/**
 * 浼佷笟寰俊鍥炶皟鍔犺В瀵?鈥?WXBizMsgCrypt
 * 鍙傝€? https://developer.work.weixin.qq.com/document/path/90968
 */
public class WeChatWorkCryptoUtil {

    private final byte[] aesKey;
    private final String token;
    private final String corpId;

    public WeChatWorkCryptoUtil(String encodingAesKey, String token, String corpId) {
        this.token = token;
        this.corpId = corpId;
        String aesKeyBase64 = encodingAesKey + "=";
        this.aesKey = Base64.getDecoder().decode(aesKeyBase64);
        if (this.aesKey.length != 32) {
            throw new IllegalArgumentException("encodingAESKey must decode to 32 bytes");
        }
    }

    /** SHA1 绛惧悕楠岃瘉 */
    public boolean verifySignature(String msgSignature, String timestamp, String nonce, String echoStr) {
        String expected = sha1(token, timestamp, nonce, echoStr);
        return expected.equalsIgnoreCase(msgSignature);
    }

    /** 瑙ｅ瘑鍥炴樉娑堟伅 (URL楠岃瘉鐢? */
    public String decryptEchoStr(String echoStr) {
        byte[] encrypted = Base64.getDecoder().decode(echoStr);
        byte[] plain = decrypt(encrypted);
        // 鏍煎紡: [16B random][4B msgLen][msg][corpId]
        ByteBuffer buf = ByteBuffer.wrap(plain);
        int msgLen = buf.getInt(16);
        return new String(plain, 20, msgLen, StandardCharsets.UTF_8);
    }

    /** 瑙ｅ瘑鎺ユ敹鍒扮殑鍔犲瘑娑堟伅 */
    public String decryptMsg(String encryptXml, String msgSignature, String timestamp, String nonce) {
        if (!verifySignature(msgSignature, timestamp, nonce, encryptXml)) {
            throw new SecurityException("Signature verification failed");
        }
        byte[] encrypted = Base64.getDecoder().decode(encryptXml);
        byte[] plain = decrypt(encrypted);
        ByteBuffer buf = ByteBuffer.wrap(plain);
        int msgLen = buf.getInt(16);
        return new String(plain, 20, msgLen, StandardCharsets.UTF_8);
    }

    /** 鍔犲瘑鍥炲娑堟伅 */
    public String encryptReply(String replyXml, String timestamp, String nonce) {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        byte[] msgBytes = replyXml.getBytes(StandardCharsets.UTF_8);
        byte[] corpIdBytes = corpId.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buf = ByteBuffer.allocate(16 + 4 + msgBytes.length + corpIdBytes.length);
        buf.put(random);
        buf.putInt(msgBytes.length);
        buf.put(msgBytes);
        buf.put(corpIdBytes);

        byte[] encrypted = encrypt(buf.array());
        String encryptBase64 = Base64.getEncoder().encodeToString(encrypted);
        String signature = sha1(token, timestamp, nonce, encryptBase64);

        return String.format(
            "<xml>%s<Encrypt><![CDATA[%s]]></Encrypt>%s" +
            "<MsgSignature><![CDATA[%s]]></MsgSignature>%s" +
            "<TimeStamp><![CDATA[%s]]></TimeStamp>%s" +
            "<Nonce><![CDATA[%s]]></Nonce></xml>",
            "\n", encryptBase64, "\n", signature, "\n", timestamp, "\n", nonce
        );
    }

    private byte[] decrypt(byte[] encrypted) {
        try {
            SecretKeySpec key = new SecretKeySpec(aesKey, "AES");
            IvParameterSpec iv = new IvParameterSpec(Arrays.copyOfRange(aesKey, 0, 16));
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, iv);
            byte[] plain = cipher.doFinal(encrypted);
            // 鍘婚櫎 PKCS7 padding
            int pad = plain[plain.length - 1] & 0xFF;
            return Arrays.copyOf(plain, plain.length - pad);
        } catch (Exception e) {
            throw new RuntimeException("AES decrypt failed", e);
        }
    }

    private byte[] encrypt(byte[] plain) {
        try {
            // PKCS7 padding
            int blockSize = 32;
            int padLen = blockSize - (plain.length % blockSize);
            byte[] padded = Arrays.copyOf(plain, plain.length + padLen);
            Arrays.fill(padded, plain.length, padded.length, (byte) padLen);

            SecretKeySpec key = new SecretKeySpec(aesKey, "AES");
            IvParameterSpec iv = new IvParameterSpec(Arrays.copyOfRange(aesKey, 0, 16));
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, iv);
            return cipher.doFinal(padded);
        } catch (Exception e) {
            throw new RuntimeException("AES encrypt failed", e);
        }
    }

    private String sha1(String... strs) {
        try {
            Arrays.sort(strs);
            StringBuilder sb = new StringBuilder();
            for (String s : strs) sb.append(s);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 0xFF));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA1 failed", e);
        }
    }
}
