
package com.feisheng.bot.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 敏感信息加密工具 - 用于加密数据库中的 API Key / Secret
 * 使用 AES-256-ECB 加密，密钥通过配置注入
 */
@Component
public class EncryptionUtil {
    private static final Logger log = LoggerFactory.getLogger(EncryptionUtil.class);
    private static final String ALGORITHM = "AES/ECB/PKCS5Padding";
    private final SecretKeySpec keySpec;

    public EncryptionUtil(@Value("${app.encryption.key:0123456789abcdef0123456789abcdef}") String key) {
        byte[] keyBytes = key.length() >= 32
            ? key.substring(0, 32).getBytes(StandardCharsets.UTF_8)
            : String.format("%-32s", key).getBytes(StandardCharsets.UTF_8);
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String encryptedText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decoded = Base64.getDecoder().decode(encryptedText);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
