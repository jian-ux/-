package com.feisheng.bot.core.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentTurnRequestTest {

    @Test
    void keepsTheCustomerCurrentQuestionAlongsideContextualIntent() {
        CurrentTurnRequest request = CurrentTurnRequest.of(
            "视频教程有没有？", "点签的使用教程");

        assertEquals("视频教程有没有？", request.originalQuestion());
        assertEquals("点签的使用教程", request.contextualIntent());
        assertEquals("点签的使用教程 当前问题：视频教程有没有？",
            request.primaryRetrievalQuery("点签的使用教程"));
        assertTrue(request.promptContext().contains("原始问题：视频教程有没有？"));
        assertTrue(request.promptContext().contains("上下文补全的业务意图：点签的使用教程"));
    }

    @Test
    void doesNotDuplicateTheQuestionWhenTheRetrievalQueryAlreadyContainsIt() {
        CurrentTurnRequest request = CurrentTurnRequest.of(
            "点签视频教程有没有？", "点签的使用教程");

        assertEquals("点签视频教程有没有？",
            request.primaryRetrievalQuery("点签视频教程有没有？"));
    }

    @Test
    void keepsCanonicalRetrievalQueryWhenNoContextWasAdded() {
        CurrentTurnRequest request = CurrentTurnRequest.of(
            "我想了解一下你们的点签", "我想了解一下你们的点签");

        assertEquals("点签电子合同产品介绍 核心功能", request.primaryRetrievalQuery(
            "点签电子合同产品介绍 核心功能"));
    }
}
