package com.feisheng.bot.admin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BadCaseImprovementAdvisorTest {
    @Test
    void returnsOneActionForEveryDistinctTrigger() {
        var advice = BadCaseImprovementAdvisor.advise(
            "no_answer,GUARDRAIL,LOW_CONFIDENCE,SLOW_RESPONSE,LOW_RATING,GUARDRAIL");

        assertThat(advice).extracting(item -> item.triggerType()).containsExactly(
            "NO_ANSWER", "GUARDRAIL", "LOW_CONFIDENCE", "SLOW_RESPONSE", "LOW_RATING");
        assertThat(advice).extracting(item -> item.recommendedAction()).containsExactly(
            "KNOWLEDGE", "SAFETY_RULE", "RETRIEVAL", "PERFORMANCE", "ANSWER_REVIEW");
        assertThat(advice).allSatisfy(item -> assertThat(item.suggestion()).isNotBlank());
    }

    @Test
    void fallsBackForLegacyAndFutureTriggerTypes() {
        assertThat(BadCaseImprovementAdvisor.advise(null))
            .extracting(item -> item.triggerType())
            .containsExactly("NO_ANSWER");
        assertThat(BadCaseImprovementAdvisor.advise("NEW_SIGNAL"))
            .extracting(item -> item.recommendedAction())
            .containsExactly("MANUAL_REVIEW");
    }
}
