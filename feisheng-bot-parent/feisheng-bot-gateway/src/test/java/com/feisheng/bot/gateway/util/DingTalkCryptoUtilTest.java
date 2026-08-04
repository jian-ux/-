package com.feisheng.bot.gateway.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkCryptoUtilTest {
    @Test
    void verifiesComputedSignature() throws Exception {
        String timestamp = "1720588800000";
        String secret = "test-secret";
        String sign = DingTalkCryptoUtil.computeSignature(timestamp, secret);
        assertTrue(DingTalkCryptoUtil.verifySignature(timestamp, sign, secret));
    }

    @Test
    void rejectsAlteredSignature() throws Exception {
        String timestamp = "1720588800000";
        String secret = "test-secret";
        String sign = DingTalkCryptoUtil.computeSignature(timestamp, secret) + "x";
        assertFalse(DingTalkCryptoUtil.verifySignature(timestamp, sign, secret));
    }

    @Test
    void encryptAndDecryptPreserveMessageAndReceiveId() throws Exception {
        String aesKey = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
        String encrypted = DingTalkCryptoUtil.encrypt("{\"text\":{\"content\":\"你好\"}}", "corp-1", aesKey);

        DingTalkCryptoUtil.DecryptedPayload payload =
            DingTalkCryptoUtil.decryptPayload(encrypted, aesKey);

        assertEquals("{\"text\":{\"content\":\"你好\"}}", payload.message());
        assertEquals("corp-1", payload.receiveId());
    }
}
