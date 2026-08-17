package com.feisheng.bot.core.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAnswerTypeMatcherTest {
    @Test
    void detectsConcreteFactShapesRequestedByCustomer() {
        assertEquals(List.of(EvidenceAnswerTypeMatcher.Requirement.PRICE),
            EvidenceAnswerTypeMatcher.requirements("企业版多少钱？"));
        assertEquals(List.of(EvidenceAnswerTypeMatcher.Requirement.DURATION),
            EvidenceAnswerTypeMatcher.requirements("套餐有效期多久？"));
        assertEquals(List.of(EvidenceAnswerTypeMatcher.Requirement.QUANTITY),
            EvidenceAnswerTypeMatcher.requirements("一次最多能签多少份？"));

        assertTrue(EvidenceAnswerTypeMatcher.matches(
            EvidenceAnswerTypeMatcher.Requirement.PRICE, "专业版1999元"));
        assertTrue(EvidenceAnswerTypeMatcher.matches(
            EvidenceAnswerTypeMatcher.Requirement.DURATION, "有效期为365天"));
        assertTrue(EvidenceAnswerTypeMatcher.matches(
            EvidenceAnswerTypeMatcher.Requirement.QUANTITY, "一次最多200份"));
        assertEquals(2, EvidenceAnswerTypeMatcher.matchCount(
            EvidenceAnswerTypeMatcher.Requirement.PRICE,
            "专业版1999元，高级版3999元"));
        assertTrue(EvidenceAnswerTypeMatcher.specificity(
            EvidenceAnswerTypeMatcher.Requirement.PRICE,
            "专业版1999元，高级版3999元")
            > EvidenceAnswerTypeMatcher.specificity(
                EvidenceAnswerTypeMatcher.Requirement.PRICE,
                "新用户可免费试用，单份低至5元"));
    }

    @Test
    void doesNotTreatGenericPolicyAsConcreteValue() {
        assertFalse(EvidenceAnswerTypeMatcher.matches(
            EvidenceAnswerTypeMatcher.Requirement.PRICE, "根据套餐版本和签署量确定价格"));
        assertFalse(EvidenceAnswerTypeMatcher.matches(
            EvidenceAnswerTypeMatcher.Requirement.DURATION, "具体期限请咨询客服"));
        assertFalse(EvidenceAnswerTypeMatcher.matches(
            EvidenceAnswerTypeMatcher.Requirement.QUANTITY, "可按实际需求购买份数"));
    }
}
