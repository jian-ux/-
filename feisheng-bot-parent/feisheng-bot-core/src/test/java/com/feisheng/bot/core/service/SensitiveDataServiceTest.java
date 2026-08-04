package com.feisheng.bot.core.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataServiceTest {
    private final SensitiveDataService service = new SensitiveDataService("18689633999");

    @Test
    void redactsSupportedSensitiveDataAndReportsOnlyTypes() {
        SensitiveDataService.RedactionResult result = service.redact(
            "手机号13800138000，身份证11010519491231002X，银行卡6222 0202 0123 4567，"
                + "邮箱test.user@example.com，收货地址：海南省海口市龙华区滨海大道99号");

        assertEquals("手机号[手机号已脱敏]，身份证[身份证已脱敏]，银行卡[银行卡已脱敏]，"
            + "邮箱[邮箱已脱敏]，收货地址：[地址已脱敏]", result.text());
        assertEquals(Set.of("PHONE", "ID_CARD", "BANK_CARD", "EMAIL", "ADDRESS"),
            result.types());
        assertTrue(result.applied());
        assertFalse(result.text().contains("13800138000"));
    }

    @Test
    void preservesConfiguredOfficialHotline() {
        SensitiveDataService.RedactionResult result = service.redact(
            "客服热线18689633999，用户手机13912345678");

        assertEquals("客服热线18689633999，用户手机[手机号已脱敏]", result.text());
        assertEquals(Set.of("PHONE"), result.types());
    }

    @Test
    void doesNotTreatAddressQuestionAsAnAddressValue() {
        SensitiveDataService.RedactionResult result = service.redact("收货地址怎么修改？");

        assertFalse(result.applied());
        assertEquals("收货地址怎么修改？", result.text());
    }

    @Test
    void preservesPublicWebsiteUrlsAfterAddressLabels() {
        String reply = "点签的官网地址是https://www.fs-signature.com/，"
            + "备用网站地址：www.fs-signature.com";

        SensitiveDataService.RedactionResult result = service.redact(reply);

        assertFalse(result.applied());
        assertEquals(reply, result.text());
    }

    @Test
    void preservesWebsiteUrlWhileStillRedactingPhysicalAddress() {
        SensitiveDataService.RedactionResult result = service.redact(
            "官网地址：https://www.fs-signature.com/，"
                + "收货地址：海南省海口市龙华区滨海大道99号");

        assertTrue(result.applied());
        assertEquals("官网地址：https://www.fs-signature.com/，收货地址：[地址已脱敏]",
            result.text());
        assertEquals(Set.of("ADDRESS"), result.types());
    }
}
