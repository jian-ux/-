package com.feisheng.bot.gateway.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
