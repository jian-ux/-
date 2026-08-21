package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextCorrectionServiceTest {
    private final TextCorrectionService service = new TextCorrectionService();

    @Test
    void correctsHighConfidenceDomainTypos() {
        assertEquals("电子合同怎么发起合同和签署？", service.correct("电子合通怎么发启合同和签暑？"));
        assertEquals("企业认证后怎么下载模板？", service.correct("企业认正后怎么下载模版？"));
    }

    @Test
    void leavesNormalTextAndBlankInputUnchanged() {
        assertEquals("合同怎么签署？", service.correct("合同怎么签署？"));
        assertEquals("  ", service.correct("  "));
        assertEquals(null, service.correct(null));
    }
}
