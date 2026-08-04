package com.feisheng.bot.knowledge.controller;

import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.knowledge.entity.BotKnowledgeChunk;
import com.feisheng.bot.knowledge.entity.BotKnowledgeItem;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.knowledge.service.KnowledgeIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeItemControllerTest {
    @Mock private BotKnowledgeItemMapper mapper;
    @Mock private BotKnowledgeChunkMapper chunkMapper;
    @Mock private KnowledgeIndexService indexService;

    private KnowledgeItemController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeItemController(mapper, chunkMapper, indexService);
    }

    @Test
    void matchesCommonChineseQuestionRephrasing() {
        BotKnowledgeItem item = new BotKnowledgeItem();
        item.setId(15L);
        item.setQuestion("我们主要是做什么的？");
        item.setAnswer("我们主要提供电子签章相关产品。 ");
        item.setKeywords("公司主要是做什么的");
        item.setStatus(1);
        when(mapper.selectList(any())).thenReturn(List.of(item));

        R<Map<String, Object>> response = controller.match(Map.of(
            "text", "你们公司主要做什么？",
            "trackHit", false));

        assertEquals(15L, response.getData().get("itemId"));
        assertEquals(1.0, response.getData().get("score"));
        assertEquals(true, response.getData().get("exactMatch"));
        assertEquals(item.getAnswer(), response.getData().get("answer"));
    }

    @Test
    void prefersTheNamedProductOverAGenericCompanyQuestion() {
        BotKnowledgeItem company = item(15L, "你们公司主要是做什么的？",
            "我们提供电子签章相关产品。", "公司是做什么");
        BotKnowledgeItem dianqian = item(23L, "点签是什么？",
            "点签是一款电子合同与电子签章产品。", "点签的介绍");
        when(mapper.selectList(any())).thenReturn(List.of(company, dianqian));

        R<Map<String, Object>> response = controller.match(Map.of(
            "text", "你们公司的点签主要是做什么的？",
            "trackHit", false));

        assertEquals(23L, response.getData().get("itemId"));
        assertEquals(1.0, response.getData().get("score"));
        assertTrue((Boolean) response.getData().get("exactMatch"));
        assertEquals("exact_question", response.getData().get("matchMode"));
    }

    @Test
    void doesNotTreatAnIntentSuffixAsTheSameQuestion() {
        BotKnowledgeItem company = item(15L, "你们公司主要是做什么的？",
            "我们提供电子签章相关产品。", "公司是做什么");
        when(mapper.selectList(any())).thenReturn(List.of(company));

        R<Map<String, Object>> response = controller.match(Map.of(
            "text", "你们公司的点签主要是做什么的？",
            "trackHit", false));

        assertEquals(15L, response.getData().get("itemId"));
        assertEquals(0.4, response.getData().get("score"));
        assertFalse((Boolean) response.getData().get("exactMatch"));
    }

    @Test
    void recallsASpecificPhraseEvenWhenKeywordsAreAliases() {
        BotKnowledgeItem advantages = item(24L, "点签有哪些产品优势？",
            "点签支持全流程电子签约、多端使用和系统集成。",
            "点签优势,产品优势,产品的优势,平台优势,产品特点,平台特点,为什么选择点签,点签有什么特点");
        when(mapper.selectList(any())).thenReturn(List.of(advantages));

        R<Map<String, Object>> response = controller.match(Map.of(
            "text", "介绍一下你们产品的优势",
            "trackHit", false));

        assertEquals(24L, response.getData().get("itemId"));
        assertEquals(0.76, response.getData().get("score"));
        assertEquals("keyword", response.getData().get("matchMode"));
        assertFalse((Boolean) response.getData().get("exactMatch"));
    }

    @Test
    void doesNotReturnExactFaqOutsideRequestedCategory() {
        BotKnowledgeItem faq = item(
            31L, "How do I reset my password?", "Use the reset link.", "password reset");
        faq.setCategoryId(10L);
        when(mapper.selectList(any())).thenReturn(List.of(faq));

        R<Map<String, Object>> response = controller.match(Map.of(
            "text", "How do I reset my password?",
            "trackHit", false,
            "filters", Map.of("categoryId", 20)));

        assertTrue(response.getData().isEmpty());
    }

    @Test
    void returnsExactFaqInsideRequestedCategoryWithTrustedMetadata() {
        BotKnowledgeItem faq = item(
            31L, "How do I reset my password?", "Use the reset link.", "password reset");
        faq.setCategoryId(20L);
        when(mapper.selectList(any())).thenReturn(List.of(faq));

        R<Map<String, Object>> response = controller.match(Map.of(
            "text", "How do I reset my password?",
            "trackHit", false,
            "filters", Map.of(
                "type", "item", "sourceType", "faq", "categoryId", 20)));

        assertEquals(31L, response.getData().get("itemId"));
        assertEquals("item", response.getData().get("type"));
        assertEquals("faq", response.getData().get("sourceType"));
        assertEquals(20L, response.getData().get("categoryId"));
        assertEquals(1.0, response.getData().get("score"));
    }

    @Test
    void returnsOnlyApprovedAdjacentChunks() {
        BotKnowledgeChunk before = chunk(90L, 8L, 3, "上一段");
        BotKnowledgeChunk anchor = chunk(91L, 8L, 4, "命中段");
        BotKnowledgeChunk after = chunk(92L, 8L, 5, "下一段");
        when(chunkMapper.selectList(any())).thenReturn(List.of(before, anchor, after));

        R<List<Map<String, Object>>> response = controller.neighbors(Map.of(
            "documentId", 8L,
            "chunkIndex", 4,
            "radius", 1));

        assertEquals(2, response.getData().size());
        assertEquals(List.of(90L, 92L), response.getData().stream()
            .map(value -> ((Number) value.get("chunkId")).longValue()).toList());
        assertTrue(response.getData().stream()
            .allMatch(value -> "chunk".equals(value.get("type"))));
    }

    @Test
    void forwardsPayloadFiltersToRetrievalService() {
        Map<String, Object> filters = Map.of("categoryId", 12, "sourceScope", "TENANT");
        when(indexService.searchLexical(anyString(), anyInt(), anyDouble(), anyMap()))
            .thenReturn(List.of());

        controller.lexicalMatch(Map.of(
            "text", "password reset", "topK", 5, "minScore", 0.5, "filters", filters));

        verify(indexService).searchLexical("password reset", 5, 0.5, filters);
    }

    @Test
    void limitsNeighborsToRequestedSectionPath() {
        BotKnowledgeChunk sameSection = chunk(90L, 8L, 3, "same section");
        sameSection.setSectionPath("Account > Security");
        BotKnowledgeChunk anchor = chunk(91L, 8L, 4, "anchor");
        anchor.setSectionPath("Account > Security");
        BotKnowledgeChunk otherSection = chunk(92L, 8L, 5, "other section");
        otherSection.setSectionPath("Account > Billing");
        when(chunkMapper.selectList(any())).thenReturn(
            List.of(sameSection, anchor, otherSection));

        R<List<Map<String, Object>>> response = controller.neighbors(Map.of(
            "documentId", 8L, "chunkIndex", 4, "radius", 1,
            "sectionPath", "Account > Security"));

        assertEquals(List.of(90L), response.getData().stream()
            .map(value -> ((Number) value.get("chunkId")).longValue()).toList());
    }

    private BotKnowledgeItem item(Long id, String question, String answer, String keywords) {
        BotKnowledgeItem item = new BotKnowledgeItem();
        item.setId(id);
        item.setQuestion(question);
        item.setAnswer(answer);
        item.setKeywords(keywords);
        item.setStatus(1);
        return item;
    }

    private BotKnowledgeChunk chunk(Long id, Long documentId, int index, String content) {
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(id);
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setStatus("APPROVED");
        return chunk;
    }
}
