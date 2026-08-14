package com.feisheng.bot.knowledge.service;

import com.feisheng.bot.knowledge.entity.BotKnowledgeChunk;
import com.feisheng.bot.knowledge.entity.BotKnowledgeDocument;
import com.feisheng.bot.knowledge.entity.BotKnowledgeItem;
import com.feisheng.bot.knowledge.entity.BotKnowledgeItemChunk;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeChunkMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeDocumentMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeItemMapper;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeItemChunkMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeIndexServiceTest {
    @Test
    void carriesMetadataAndAppliesFiltersAcrossMemoryRetrievalModes() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeItem faq = item(1L, "password reset instructions", "faq answer", "[1,0]");
        faq.setCategoryId(10L);
        BotKnowledgeChunk chunk = chunk(
            2L, 9L, 0, "password reset instructions", "[1,0]");
        BotKnowledgeDocument document = document(9L, "Account manual");
        document.setCategoryId(20L);
        document.setSourceScope("TENANT");
        document.setExpiresAt(Date.from(Instant.parse("2027-01-02T03:04:05Z")));
        when(itemMapper.selectList(any())).thenReturn(List.of(faq));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(documentMapper.selectList(null)).thenReturn(List.of(document));
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        service.sync();

        Map<String, Object> documentFilters = Map.of(
            "categoryId", 20, "sourceScope", List.of("PUBLIC", "TENANT"));
        List<List<java.util.Map<String, Object>>> results = List.of(
            service.search(List.of(1.0, 0.0), 1, 0.5, documentFilters),
            service.searchLexical("password reset instructions", 1, 0.5, documentFilters),
            service.searchPhonetic("password reset instructions", 1, 0.5, documentFilters),
            service.searchBm25("password reset instructions", 1, 0.0, documentFilters));

        assertTrue(results.stream().allMatch(values -> values.size() == 1));
        assertTrue(results.stream().allMatch(values -> Long.valueOf(2L).equals(
            values.get(0).get("chunkId"))));
        assertTrue(results.stream().allMatch(values ->
            "2027-01-02T03:04:05Z".equals(values.get(0).get("expiresAt"))));
        Map<String, Object> faqFilters = Map.of(
            "categoryId", 10, "sourceScope", "KNOWLEDGE");
        List<List<java.util.Map<String, Object>>> faqResults = List.of(
            service.search(List.of(1.0, 0.0), 1, 0.5, faqFilters),
            service.searchLexical("password reset instructions", 1, 0.5, faqFilters),
            service.searchPhonetic("password reset instructions", 1, 0.5, faqFilters),
            service.searchBm25("password reset instructions", 1, 0.0, faqFilters));
        assertTrue(faqResults.stream().allMatch(values -> values.size() == 1));
        assertTrue(faqResults.stream().allMatch(values -> Long.valueOf(1L).equals(
            values.get(0).get("itemId"))));
        assertTrue(faqResults.stream().allMatch(values -> "KNOWLEDGE".equals(
            values.get(0).get("sourceScope"))));
        assertTrue(faqResults.stream().allMatch(values -> Long.valueOf(10L).equals(
            values.get(0).get("categoryId"))));
    }

    @Test
    void indexesLongFaqChildVectorUnderParentFaqIdentity() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeItemChunkMapper itemChunkMapper = mock(BotKnowledgeItemChunkMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(itemMapper.selectList(any())).thenReturn(List.of(
            item(7L, "长答案如何查询", "答案开头", "[1,0]")));
        BotKnowledgeItemChunk child = new BotKnowledgeItemChunk();
        child.setId(70L);
        child.setItemId(7L);
        child.setChunkIndex(1);
        child.setContent("答案尾部的专项检索词");
        child.setEmbedding("[0,1]");
        when(itemChunkMapper.selectList(any())).thenReturn(List.of(child));
        when(chunkMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(documentMapper.selectList(null)).thenReturn(Collections.emptyList());
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, itemChunkMapper, chunkMapper, documentMapper,
            new ObjectMapper(), disabledQdrant());

        KnowledgeIndexService.SyncReport report = service.sync();
        List<java.util.Map<String, Object>> result = service.search(
            List.of(0.0, 1.0), 1, 0.5, Map.of("sourceScope", "KNOWLEDGE"));

        assertEquals(2, report.faqVectors());
        assertEquals(7L, result.get(0).get("itemId"));
        assertEquals(70L, result.get(0).get("faqChunkId"));
        assertEquals("KNOWLEDGE", result.get(0).get("sourceScope"));
        assertEquals("答案尾部的专项检索词", result.get(0).get("answer"));
    }

    @Test
    void syncsFaqAndDocumentMetadataAndReportsDiff() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(itemMapper.selectList(any())).thenReturn(List.of(item(1L, "密码问题", "点击忘记密码", "[1,0]")));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk(2L, 9L, 3, "年假为五天", "[0,1]")));
        when(documentMapper.selectList(null)).thenReturn(List.of(document(9L, "员工手册")));
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());

        KnowledgeIndexService.SyncReport report = service.sync();

        assertTrue(report.success());
        assertEquals(1, report.version());
        assertEquals(2, report.added());
        assertEquals(1, report.faqVectors());
        assertEquals(1, report.chunkVectors());
        List<java.util.Map<String, Object>> result = service.search(List.of(0.0, 1.0), 1, 0.5);
        assertEquals("document", result.get(0).get("sourceType"));
        assertEquals("员工手册", result.get(0).get("title"));
        assertEquals(3, result.get(0).get("chunkIndex"));
    }

    @Test
    void keepsLastGoodSnapshotWhenSyncFails() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(itemMapper.selectList(any()))
            .thenReturn(List.of(item(1L, "密码问题", "点击忘记密码", "[1,0]")))
            .thenThrow(new IllegalStateException("database unavailable"));
        when(chunkMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(documentMapper.selectList(null)).thenReturn(Collections.emptyList());
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        service.sync();

        KnowledgeIndexService.SyncReport failed = service.sync();

        assertFalse(failed.success());
        assertEquals(1, failed.version());
        assertEquals(1, failed.faqVectors());
        assertEquals(1, service.search(List.of(1.0, 0.0), 3, 0.5).size());
    }

    @Test
    void marksOcrChunksAsImageSources() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(itemMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(5L, 12L, 0, "截图显示订单状态为已完成", "[1,0]")));
        BotKnowledgeDocument image = document(12L, "订单截图.png");
        image.setMediaType("IMAGE");
        when(documentMapper.selectList(null)).thenReturn(List.of(image));
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        service.sync();

        List<java.util.Map<String, Object>> result = service.search(List.of(1.0, 0.0), 1, 0.5);

        assertEquals("image", result.get(0).get("sourceType"));
        assertEquals("IMAGE", result.get(0).get("mediaType"));
        assertEquals("/api/admin/doc/12/preview", result.get(0).get("previewUrl"));
    }

    @Test
    void fallsBackToMemoryWhenQdrantSearchFails() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(itemMapper.selectList(any())).thenReturn(List.of(
            item(1L, "password reset", "use the reset link", "[1,0]")));
        when(chunkMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(documentMapper.selectList(null)).thenReturn(Collections.emptyList());

        QdrantVectorStore qdrant = mock(QdrantVectorStore.class);
        when(qdrant.isEnabled()).thenReturn(true);
        when(qdrant.reconcile(any())).thenReturn(new QdrantVectorStore.ReconcileResult(1, 0));
        when(qdrant.search(any(), anyInt(), anyDouble()))
            .thenThrow(new IllegalStateException("qdrant unavailable"));
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), qdrant);

        KnowledgeIndexService.SyncReport report = service.sync();
        List<java.util.Map<String, Object>> result = service.search(List.of(1.0, 0.0), 1, 0.5);

        assertTrue(report.qdrantSynced());
        assertEquals("use the reset link", result.get(0).get("answer"));
        assertEquals("memory", service.status().searchBackend());
    }

    @Test
    void excludesLowInformationGreetingFaqFromSemanticIndex() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(itemMapper.selectList(any())).thenReturn(List.of(
            item(1L, "你好", "您好，我是智能客服", "[1,0]"),
            item(2L, "如何重置密码", "点击忘记密码", "[0,1]")));
        when(chunkMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(documentMapper.selectList(null)).thenReturn(Collections.emptyList());
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());

        KnowledgeIndexService.SyncReport report = service.sync();
        List<java.util.Map<String, Object>> result = service.search(
            List.of(1.0, 0.0), 3, -1.0);

        assertEquals(1, report.faqVectors());
        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).get("itemId"));
    }

    @Test
    void matchesHomophoneTyposInsideDocumentChunks() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(itemMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(5L, 12L, 0,
                "你们还要收费，别人家都免费的。我们的服务包含司法存证。", "[1,0]")));
        when(documentMapper.selectList(null)).thenReturn(List.of(document(12L, "产品问答")));
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        service.sync();

        List<java.util.Map<String, Object>> result = service.searchPhonetic(
            "尼门孩耀手废，憋人在家豆免废地", 3, 0.80);

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).get("chunkId"));
        assertEquals("phonetic", result.get(0).get("matchMode"));
        assertTrue(((Number) result.get(0).get("similarity")).doubleValue() >= 0.80);
    }

    @Test
    void matchesLexicalQuestionVariantInsideDocumentChunks() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(itemMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(7L, 20L, 1,
                "管理员完成个人认证后，可以创建企业。企业怎么认证？支持法人认证和对公打款。",
                "[1,0]")));
        when(documentMapper.selectList(null)).thenReturn(List.of(document(20L, "点签问答库")));
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        service.sync();

        List<java.util.Map<String, Object>> result = service.searchLexical(
            "企业认证怎么认证？", 3, 0.72);

        assertEquals(1, result.size());
        assertEquals(7L, result.get(0).get("chunkId"));
        assertEquals("lexical", result.get(0).get("matchMode"));
        assertTrue(((Number) result.get(0).get("similarity")).doubleValue() >= 0.72);
    }

    @Test
    void ranksExactQuestionInChunkAboveGenericCompetitorFaq() {
        String query = "e签宝说你们是小平台";
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        when(itemMapper.selectList(any())).thenReturn(List.of(
            item(17L, "你们和其他电子合同平台相比哪个好？",
                "请根据具体需求比较产品能力。", "[1,0]")));
        when(chunkMapper.selectList(any())).thenReturn(List.of(
            chunk(566L, 20L, 54,
                "e签宝说你们是小平台\n平台规模只是选型因素之一，更重要的是业务匹配。",
                "[0,1]")));
        when(documentMapper.selectList(null)).thenReturn(List.of(document(20L, "点签SaaS客户问答库")));
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        service.sync();

        List<java.util.Map<String, Object>> result = service.searchLexical(query, 10, 0.0);

        assertEquals(2, result.size());
        assertEquals(566L, result.get(0).get("chunkId"));
        assertEquals(1.0, ((Number) result.get(0).get("lexicalScore")).doubleValue());
    }

    @Test
    void reportsAndCanRejectMixedEmbeddingModelVersions() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeItem faq = item(1L, "合同怎么签", "完成认证后发起", "[1,0]");
        faq.setEmbeddingVersion("model-v1");
        BotKnowledgeChunk chunk = chunk(2L, 9L, 0, "合同签署流程", "[0,1]");
        chunk.setEmbeddingVersion("model-v2");
        when(itemMapper.selectList(any())).thenReturn(List.of(faq));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(documentMapper.selectList(null)).thenReturn(List.of(document(9L, "签署手册")));
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());

        KnowledgeIndexService.SyncReport report = service.sync();

        assertTrue(report.success());
        assertFalse(service.status().embeddingConsistency().consistent());
        assertEquals(List.of("model-v1", "model-v2"),
            service.status().embeddingConsistency().versions());

        KnowledgeIndexService strictService = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        ReflectionTestUtils.setField(strictService, "requireConsistentEmbeddingVersion", true);
        assertFalse(strictService.sync().success());
        assertEquals(0, strictService.status().faqVectors());
    }

    @Test
    void marksUniqueReviewedStructuredQaAsDirectEligible() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunk qa = qaChunk(21L, 8L, "如何开票？", "第一步提交信息。第二步下载发票。",
            1, "[1,0]");
        when(itemMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(chunkMapper.selectList(any())).thenReturn(List.of(qa));
        when(documentMapper.selectList(null)).thenReturn(List.of(document(8L, "开票说明")));
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());

        service.sync();
        java.util.Map<String, Object> result = service.search(List.of(1.0, 0.0), 1, 0.5).get(0);

        assertEquals(true, result.get("structuredQa"));
        assertEquals(true, result.get("directAnswerEligible"));
        assertEquals("eligible", result.get("qaDirectStatus"));
        assertEquals("第一步提交信息。第二步下载发票。", result.get("fullAnswer"));
    }

    @Test
    void blocksStructuredQaDirectWhenCurrentVersionAnswersConflict() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunk first = qaChunk(21L, 8L, "如何开票？", "提交资料后开票。", 1, "[1,0]");
        BotKnowledgeChunk second = qaChunk(22L, 9L, "如何开票？", "暂不支持开票。", 1, "[0,1]");
        when(itemMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(chunkMapper.selectList(any())).thenReturn(List.of(first, second));
        when(documentMapper.selectList(null)).thenReturn(List.of(
            document(8L, "开票说明"), document(9L, "旧版说明")));
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());

        service.sync();
        List<java.util.Map<String, Object>> results = service.search(List.of(1.0, 0.0), 2, -1.0);

        assertTrue(results.stream().allMatch(result -> Boolean.TRUE.equals(result.get("qaConflict"))));
        assertTrue(results.stream().noneMatch(result -> Boolean.TRUE.equals(
            result.get("directAnswerEligible"))));
    }

    @Test
    void onlyHighestReviewedStructuredQaVersionCanDirectAnswer() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunk old = qaChunk(21L, 8L, "如何开票？", "旧答案。", 1, "[1,0]");
        BotKnowledgeChunk current = qaChunk(22L, 9L, "如何开票？", "新答案。", 2, "[0,1]");
        when(itemMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(chunkMapper.selectList(any())).thenReturn(List.of(old, current));
        when(documentMapper.selectList(null)).thenReturn(List.of(
            document(8L, "旧版"), document(9L, "新版")));
        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());

        service.sync();
        List<java.util.Map<String, Object>> results = service.search(List.of(1.0, 0.0), 2, -1.0);
        java.util.Map<String, Object> oldResult = results.stream()
            .filter(result -> Long.valueOf(21L).equals(result.get("chunkId"))).findFirst().orElseThrow();
        java.util.Map<String, Object> currentResult = results.stream()
            .filter(result -> Long.valueOf(22L).equals(result.get("chunkId"))).findFirst().orElseThrow();

        assertEquals("superseded", oldResult.get("qaDirectStatus"));
        assertEquals(false, oldResult.get("directAnswerEligible"));
        assertEquals("eligible", currentResult.get("qaDirectStatus"));
        assertEquals(true, currentResult.get("directAnswerEligible"));
    }

    @Test
    void indexesOnlyPublishedAndEffectiveDocumentVersions() {
        BotKnowledgeItemMapper itemMapper = mock(BotKnowledgeItemMapper.class);
        BotKnowledgeChunkMapper chunkMapper = mock(BotKnowledgeChunkMapper.class);
        BotKnowledgeDocumentMapper documentMapper = mock(BotKnowledgeDocumentMapper.class);
        BotKnowledgeChunk publishedChunk = chunk(31L, 11L, 0, "线上有效答案", "[1,0]");
        BotKnowledgeChunk draftChunk = chunk(32L, 12L, 0, "尚未发布答案", "[0,1]");
        BotKnowledgeChunk expiredChunk = chunk(33L, 13L, 0, "已经过期答案", "[0.5,0.5]");
        when(itemMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(chunkMapper.selectList(any())).thenReturn(
            List.of(publishedChunk, draftChunk, expiredChunk));

        BotKnowledgeDocument published = document(11L, "产品手册 v1");
        published.setPublishStatus("PUBLISHED");
        published.setKnowledgeSetKey("product-manual");
        published.setDocumentVersion(1);
        published.setPriority(10);
        BotKnowledgeDocument draft = document(12L, "产品手册 v2");
        draft.setPublishStatus("DRAFT");
        BotKnowledgeDocument expired = document(13L, "产品手册旧版");
        expired.setPublishStatus("PUBLISHED");
        expired.setEffectiveTo(Date.from(Instant.now().minusSeconds(60)));
        when(documentMapper.selectList(null)).thenReturn(List.of(published, draft, expired));

        KnowledgeIndexService service = new KnowledgeIndexService(
            itemMapper, chunkMapper, documentMapper, new ObjectMapper(), disabledQdrant());
        KnowledgeIndexService.SyncReport report = service.sync();
        List<java.util.Map<String, Object>> results = service.search(
            List.of(1.0, 0.0), 10, -1.0);

        assertEquals(1, report.chunkVectors());
        assertEquals(1, results.size());
        assertEquals(31L, results.get(0).get("chunkId"));
        assertEquals("product-manual", results.get(0).get("knowledgeSetKey"));
        assertEquals(1, results.get(0).get("documentVersion"));
        assertEquals(10, results.get(0).get("documentPriority"));
    }

    private QdrantVectorStore disabledQdrant() {
        QdrantVectorStore qdrant = mock(QdrantVectorStore.class);
        when(qdrant.isEnabled()).thenReturn(false);
        return qdrant;
    }

    private BotKnowledgeItem item(Long id, String question, String answer, String embedding) {
        BotKnowledgeItem item = new BotKnowledgeItem();
        item.setId(id);
        item.setQuestion(question);
        item.setAnswer(answer);
        item.setEmbedding(embedding);
        item.setStatus(1);
        return item;
    }

    private BotKnowledgeChunk chunk(Long id, Long documentId, int index,
                                    String content, String embedding) {
        BotKnowledgeChunk chunk = new BotKnowledgeChunk();
        chunk.setId(id);
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setEmbedding(embedding);
        chunk.setStatus("APPROVED");
        return chunk;
    }

    private BotKnowledgeChunk qaChunk(Long id, Long documentId, String question,
                                      String answer, int version, String embedding) {
        BotKnowledgeChunk chunk = chunk(id, documentId, 0,
            question + "\n" + answer, embedding);
        chunk.setContentType("QA");
        chunk.setQaQuestion(question);
        chunk.setQaAnswer(answer);
        chunk.setQaVersion(version);
        chunk.setDirectAnswerEnabled(1);
        return chunk;
    }

    private BotKnowledgeDocument document(Long id, String title) {
        BotKnowledgeDocument document = new BotKnowledgeDocument();
        document.setId(id);
        document.setTitle(title);
        return document;
    }
}
