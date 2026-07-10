package com.feisheng.bot.gateway.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 閽夐拤鍥炶皟绛惧悕楠岃瘉
 * 鍙傝€? https://open.dingtalk.com/document/robots/configure-outgoing-robot
 */
public class DingTalkCryptoUtil {

    /**
     * 楠岃瘉閽夐拤鍥炶皟绛惧悕
     * @param timestamp 璇锋眰澶?timestamp
     * @param sign      璇锋眰澶?sign
     * @param appSecret 搴旂敤 Secret
     * @return true 楠岃瘉閫氳繃
     */
    public static boolean verifySignature(String timestamp, String sign, String appSecret) {
        try {
            String expected = computeSignature(timestamp, appSecret);
            return expected.equals(sign);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 璁＄畻閽夐拤鍥炶皟绛惧悕
     * sign = URLEncode(Base64(HMAC-SHA256(timestamp + "\n" + appSecret)))
     */
    public static String computeSignature(String timestamp, String appSecret) throws Exception {
        String stringToSign = timestamp + "\n" + appSecret;
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec key = new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(key);
        byte[] signBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String base64Sign = Base64.getEncoder().encodeToString(signBytes);
        return URLEncoder.encode(base64Sign, "UTF-8");
    }
}
