package com.feisheng.bot.core.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeQuestionPlannerTest {

    private final CompositeQuestionPlanner planner = new CompositeQuestionPlanner();

    @Test
    void decomposesIndependentQuestionsAndCarriesTheExplicitProductSubject() {
        CompositeQuestionPlanner.Plan plan = planner.plan("点签企业认证需要哪些材料，支持批量发起合同吗？");

        assertTrue(plan.composite());
        assertEquals(List.of(
                "点签企业认证需要哪些材料",
                "点签支持批量发起合同吗"), plan.queries());
    }

    @Test
    void keepsACompoundCapabilityStatementAsOneQuestion() {
        CompositeQuestionPlanner.Plan plan = planner.plan("点签支持合同发起和在线签署吗？");

        assertFalse(plan.composite());
        assertEquals(List.of("点签支持合同发起和在线签署吗"), plan.queries());
    }

    @Test
    void doesNotTreatAProcedureWithOneQuestionAsMultipleIntents() {
        CompositeQuestionPlanner.Plan plan = planner.plan("企业认证需要提交哪些材料和完成哪些步骤？");

        assertFalse(plan.composite());
        assertEquals(List.of("企业认证需要提交哪些材料和完成哪些步骤"), plan.queries());
    }
}
