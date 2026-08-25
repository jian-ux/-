package com.feisheng.bot.admin.service;

import com.feisheng.bot.common.util.KnowledgeTextUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeTextUtilTest {
    @Test
    void splitsLongFaqWithoutLosingItsTailAndRepeatsQuestionContext() {
        String answer = "正文".repeat(2500) + "尾部关键事实";

        List<KnowledgeTextUtil.FaqEmbeddingPart> parts =
            KnowledgeTextUtil.faqEmbeddingParts("如何查询长答案？", "长答案", answer);

        assertTrue(parts.size() > 1);
        assertTrue(parts.stream().allMatch(part -> part.embeddingText().length() <= 2000));
        assertTrue(parts.stream().allMatch(part -> part.embeddingText().startsWith(
            "如何查询长答案？\n长答案\n")));
        assertTrue(parts.get(parts.size() - 1).answerPart().endsWith("尾部关键事实"));
        assertEquals(answer, parts.stream()
            .map(KnowledgeTextUtil.FaqEmbeddingPart::answerPart)
            .reduce("", String::concat));
    }

    @Test
    void includesPublishedQuestionAliasesInFaqEmbeddingText() {
        String text = KnowledgeTextUtil.faqEmbeddingText(
            "套餐怎么收费？", "价格", "按套餐份数收费。", "[\"电子合同多少钱一份？\"]");

        assertTrue(text.contains("套餐怎么收费？\n价格\n电子合同多少钱一份？"));
    }
}
