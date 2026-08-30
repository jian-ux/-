package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.common.util.RedisUtil;
import com.feisheng.bot.core.client.KnowledgeClient;
import com.feisheng.bot.core.client.StructuredUnitRetrievalClient;
import com.feisheng.bot.core.client.StructuredUnitRetrievalClient.StructuredUnitHit;
import com.feisheng.bot.core.dto.QueryVariant;
import com.feisheng.bot.core.service.EmbeddingService;
import com.feisheng.bot.core.service.BusinessSafetyBoundaryService;
import com.feisheng.bot.core.service.QueryExpansionService;
import com.feisheng.bot.core.service.RerankService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRetrievalServiceTest {
    @Mock private FaqMatchServiceImpl faqMatchService;
    @Mock private EmbeddingService embeddingService;
    @Mock private KnowledgeClient knowledgeClient;
    @Mock private RerankService rerankService;
    @Mock private QueryExpansionService queryExpansionService;
    @Mock private StructuredUnitRetrievalClient structuredUnitRetrievalClient;
    @Mock private RedisUtil redisUtil;

    private RagRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new RagRetrievalService(faqMatchService, embeddingService,
            knowledgeClient, rerankService, queryExpansionService,
            structuredUnitRetrievalClient, redisUtil, new ObjectMapper(),
            new BusinessSafetyBoundaryService());
        configure(service);
        lenient().when(queryExpansionService.expand(anyString(), anyBoolean()))
            .thenAnswer(invocation -> List.of(QueryVariant.original(invocation.getArgument(0))));
        lenient().when(knowledgeClient.neighborChunks(any(), anyInt(), anyInt(), anyString()))
            .thenReturn(Collections.emptyList());
    }

    private void configure(RagRetrievalService target) {
        ReflectionTestUtils.setField(target, "topK", 3);
        ReflectionTestUtils.setField(target, "candidateK", 10);
        ReflectionTestUtils.setField(target, "directThreshold", 0.82);
        ReflectionTestUtils.setField(target, "contextThreshold", 0.62);
        ReflectionTestUtils.setField(target, "lexicalThreshold", 0.72);
        ReflectionTestUtils.setField(target, "bm25Enabled", false);
        ReflectionTestUtils.setField(target, "bm25FallbackMinScore", 8.0);
        ReflectionTestUtils.setField(target, "bm25FallbackMinGapRatio", 1.10);
        ReflectionTestUtils.setField(target, "bm25FallbackMinQuestionSimilarity", 0.60);
        ReflectionTestUtils.setField(target, "bm25FallbackConfidence", 0.65);
        ReflectionTestUtils.setField(target, "rankFusionK", 60);
        ReflectionTestUtils.setField(target, "neighborRadius", 1);
        ReflectionTestUtils.setField(target, "maxNeighborChunks", 4);
        ReflectionTestUtils.setField(target, "rerankHighMinScore", 0.90);
        ReflectionTestUtils.setField(target, "rerankHighMinGap", 0.08);
        ReflectionTestUtils.setField(target, "rerankMediumMinScore", 0.65);
        ReflectionTestUtils.setField(target, "rerankMediumMinGap", 0.03);
        ReflectionTestUtils.setField(target, "structuredUnitIndexEnabled", false);
        ReflectionTestUtils.setField(target, "structuredUnitShadowOnly", true);
        ReflectionTestUtils.setField(target, "structuredUnitTopK", 5);
        ReflectionTestUtils.setField(target, "structuredUnitWeight", 0.65);
    }

    @Test
    void acceptsDocumentChunkAndBuildsStructuredCitation() {
        when(faqMatchService.match(anyString(), eq(true))).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(anyString(), eq(List.of(1.0, 0.0)), eq(10)))
            .thenReturn(List.of(chunk(9L, 4L, "员工手册", 0.74)));

        RagRetrievalService.RetrievalResult result = service.retrieve("年假有几天");

        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals("rag", result.decision());
        assertEquals(0.74, result.confidence());
        assertEquals("document", result.citations().get(0).get("sourceType"));
        assertEquals(9L, result.citations().get(0).get("sourceId"));
        assertEquals("员工手册", result.citations().get(0).get("title"));
        assertTrue(result.context().contains("事实：员工手册"));
        assertTrue(result.context().contains("不要输出引用或参考来源"));
    }

    @Test
    void promotesConcretePriceEvidenceWhenRerankerIsUnavailable() {
        String query = "点签企业版多少钱？";
        ReflectionTestUtils.setField(service, "contextThreshold", 0.50);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> packageRule = structuredQa(1L, 63L,
            "怎么买套餐？", "企业可根据需求选择套餐份数购买。", 0.70, false, false);
        Map<String, Object> genericPrice = structuredQa(2L, 63L,
            "收费标准是什么？", "新用户可免费试用，单份低至5元。", 0.68, false, false);
        Map<String, Object> volumeRule = structuredQa(3L, 63L,
            "一年签署量是多少？", "200份以下按标准套餐购买，以上对接客户经理。", 0.66, false, false);
        Map<String, Object> concretePrice = structuredQa(4L, 63L,
            "专业版和高级版的价格？",
            "专业版1999元；高级版3999元。", 0.63, false, false);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(packageRule, genericPrice, volumeRule, concretePrice));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals(4L, result.citations().get(0).get("sourceId"));
        assertTrue(result.context().contains("专业版1999元"));
        Map<String, Object> diagnostic = result.candidates().stream()
            .filter(candidate -> Long.valueOf(4L).equals(candidate.get("sourceId")))
            .findFirst().orElseThrow();
        assertEquals("price", diagnostic.get("answerTypeCoverage"));
        assertEquals(true, diagnostic.get("answerTypeCoveragePromoted"));
    }

    @Test
    void promotesConcretePriceEvidenceWhenRerankerFavorsGenericCandidates() {
        String query = "点签企业版多少钱？";
        ReflectionTestUtils.setField(service, "contextThreshold", 0.50);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> packageRule = structuredQa(1L, 63L,
            "怎么买套餐？", "企业可根据需求选择套餐份数购买。", 0.70, false, false);
        Map<String, Object> genericPrice = structuredQa(2L, 63L,
            "收费标准是什么？", "新用户可免费试用，单份低至5元。", 0.68, false, false);
        Map<String, Object> volumeRule = structuredQa(3L, 63L,
            "一年签署量是多少？", "200份以下按标准套餐购买，以上对接客户经理。", 0.66, false, false);
        Map<String, Object> concretePrice = structuredQa(4L, 63L,
            "专业版和高级版的价格？",
            "专业版1999元；高级版3999元。", 0.63, false, false);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(packageRule, genericPrice, volumeRule, concretePrice));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList()))
            .thenReturn(Map.of(0, 0.95, 1, 0.85, 2, 0.75, 3, 0.40));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals(4L, result.citations().get(0).get("sourceId"));
        assertTrue(result.context().contains("专业版1999元"));
        Map<String, Object> diagnostic = result.candidates().stream()
            .filter(candidate -> Long.valueOf(4L).equals(candidate.get("sourceId")))
            .findFirst().orElseThrow();
        assertEquals("price", diagnostic.get("answerTypeCoverage"));
        assertEquals(true, diagnostic.get("answerTypeCoveragePromoted"));
        assertEquals(true, result.rerankDiagnostics().get("applied"));
    }

    @Test
    void strongKeywordMatchIsNotSuppressedByLowVectorScore() {
        Map<String, Object> keyword = new HashMap<>();
        keyword.put("itemId", 7L);
        keyword.put("question", "如何重置密码");
        keyword.put("answer", "点击忘记密码");
        keyword.put("score", 1.0);
        keyword.put("exactMatch", true);
        keyword.put("directAnswerEnabled", true);
        when(faqMatchService.match(anyString(), eq(true))).thenReturn(keyword);

        RagRetrievalService.RetrievalResult result = service.retrieve("如何重置密码");

        assertTrue(result.answerable());
        assertTrue(result.directAnswer());
        assertEquals(1.0, result.confidence());
        assertEquals("点击忘记密码", result.directAnswerText());
    }

    @Test
    void returnsCompleteReviewedUsageAnswerVerbatimOnExactMatch() {
        String question = "点签可以在哪里使用？";
        String answer = """
            应用可以通过微信、pc网页端、钉钉进入使用。
            1、微信端：搜索公众号或小程序 —— 点签电子合同
            2、PC网页端：https://ding.fs-signature.com/pc/
            3、钉钉广场搜索：点签电子合同应用开通使用""";
        Map<String, Object> keyword = new HashMap<>();
        keyword.put("itemId", 173L);
        keyword.put("question", question);
        keyword.put("answer", answer);
        keyword.put("score", 1.0);
        keyword.put("exactMatch", true);
        keyword.put("directAnswerEnabled", true);
        when(faqMatchService.match(question, true)).thenReturn(keyword);

        RagRetrievalService.RetrievalResult result = service.retrieve(question);

        assertTrue(result.answerable());
        assertTrue(result.directAnswer());
        assertEquals("direct", result.decision());
        assertEquals(answer, result.directAnswerText());
    }

    @Test
    void returnsNoAnswerBelowThreshold() {
        when(faqMatchService.match(anyString(), eq(true))).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(anyString(), eq(List.of(1.0, 0.0)), eq(10)))
            .thenReturn(List.of(chunk(9L, 4L, "员工手册", 0.31)));

        RagRetrievalService.RetrievalResult result = service.retrieve("火星办公室地址");

        assertFalse(result.answerable());
        assertEquals("no_answer", result.decision());
        assertTrue(result.citations().isEmpty());
    }

    @Test
    void bm25AndVectorRanksAreFusedWithoutUsingBm25AsAnswerConfidence() {
        String query = "ERR42证书过期";
        ReflectionTestUtils.setField(service, "bm25Enabled", true);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        Map<String, Object> generic = chunk(1L, 10L, "电子合同说明", 0.80);
        Map<String, Object> exact = chunk(2L, 10L, "ERR42错误处理", 0.78);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(generic, exact));
        Map<String, Object> sparse = new HashMap<>(exact);
        sparse.remove("similarity");
        sparse.put("bm25Score", 4.2);
        sparse.put("matchMode", "bm25");
        when(knowledgeClient.bm25Match(query, 10, 0.0)).thenReturn(List.of(sparse));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertEquals("ERR42错误处理", result.citations().get(0).get("title"),
            result.candidates().toString());
        assertEquals(0.78, result.confidence());
        assertEquals(4.2, result.candidates().get(0).get("bm25Score"));
    }

    @Test
    void acceptsClearlyLeadingAlignedBm25QuestionWhenRerankerIsUnavailable() {
        String query = "你们是做什么的？";
        ReflectionTestUtils.setField(service, "bm25Enabled", true);
        ReflectionTestUtils.setField(service, "contextThreshold", 0.50);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> callback = structuredQa(4991L, 62L,
            "请问近期使用中是否遇到问题？", "欢迎反馈使用问题。", 0.50, false, false);
        Map<String, Object> introduction = structuredQa(4801L, 62L,
            "点签是做什么的？", "点签提供电子合同全流程服务。", 0.0, false, false);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(callback));

        Map<String, Object> callbackBm25 = new HashMap<>(callback);
        callbackBm25.remove("similarity");
        callbackBm25.put("bm25Score", 13.6);
        Map<String, Object> introductionBm25 = new HashMap<>(introduction);
        introductionBm25.remove("similarity");
        introductionBm25.put("bm25Score", 40.4);
        when(knowledgeClient.bm25Match(query, 10, 0.0))
            .thenReturn(List.of(introductionBm25, callbackBm25));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals(4801L, result.citations().get(0).get("sourceId"));
        assertEquals(0.65, result.confidence());
        Map<String, Object> accepted = result.candidates().stream()
            .filter(candidate -> Long.valueOf(4801L).equals(candidate.get("sourceId")))
            .findFirst().orElseThrow();
        assertEquals(true, accepted.get("sparseFallbackAccepted"));
        assertTrue(result.context().contains("点签提供电子合同全流程服务"));
    }

    @Test
    void doesNotAcceptWeaklyAlignedBm25CandidateAsFallbackEvidence() {
        String query = "你们是做什么的？";
        ReflectionTestUtils.setField(service, "bm25Enabled", true);
        ReflectionTestUtils.setField(service, "contextThreshold", 0.62);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(false);
        Map<String, Object> callback = structuredQa(4991L, 62L,
            "近期是否遇到使用问题？", "欢迎反馈使用问题。", 0.0, false, false);
        callback.remove("similarity");
        callback.put("bm25Score", 40.0);
        when(knowledgeClient.bm25Match(query, 10, 0.0)).thenReturn(List.of(callback));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertFalse(result.answerable());
        assertEquals("no_answer", result.decision());
    }

    @Test
    void optionalCrossEncoderReranksFusedCandidates() {
        String query = "企业签合同需要什么";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        Map<String, Object> generic = chunk(1L, 10L, "合同介绍", 0.80);
        Map<String, Object> procedure = chunk(2L, 10L, "企业签署流程", 0.78);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(generic, procedure));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList())).thenReturn(Map.of(0, 0.15, 1, 0.92));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertEquals("企业签署流程", result.citations().get(0).get("title"));
        assertEquals(0.92, result.confidence());
        assertEquals(0.92, result.candidates().get(0).get("rerankScore"));
        assertEquals("HIGH", result.rerankConfidenceTier());
        assertEquals(0.77, result.candidates().get(0).get("rerankScoreGap"));
        assertEquals(true, result.rerankDiagnostics().get("configured"));
        assertEquals(true, result.rerankDiagnostics().get("attempted"));
        assertEquals(true, result.rerankDiagnostics().get("applied"));
        assertEquals("rerank", result.rerankDiagnostics().get("scoreSource"));
        assertTrue(result.stageLatencies().containsKey("retrievalTotalMs"));
        assertTrue(result.stageLatencies().containsKey("keywordMatchMs"));
        assertTrue(result.stageLatencies().containsKey("embeddingMs"));
        assertTrue(result.stageLatencies().containsKey("vectorSearchMs"));
        assertTrue(result.stageLatencies().containsKey("sparseSearchMs"));
        assertTrue(result.stageLatencies().containsKey("rerankMs"));
        assertEquals(0, result.stageLatencies().get("embeddingCacheHits"));
        assertEquals(1, result.stageLatencies().get("embeddingCacheMisses"));
        assertEquals(2, result.stageLatencies().get("candidateCount"));
        assertFalse(result.directAnswer());
    }

    @Test
    void preservesOriginalQueryAsFirstSemanticRecallWhenExpansionOmitsIt() {
        String query = "企业认证怎么操作";
        QueryVariant expanded = new QueryVariant("企业实名认证操作流程", 0.80,
            "standardized", false);
        when(queryExpansionService.expand(query, false)).thenReturn(List.of(expanded));
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(embeddingService.embed(expanded.query())).thenReturn(List.of(0.0, 1.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(chunk(31L, 10L, "原查询命中", 0.75)));
        when(knowledgeClient.semanticMatch(expanded.query(), List.of(0.0, 1.0), 10))
            .thenReturn(List.of(chunk(32L, 10L, "扩展查询命中", 0.80)));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        InOrder semanticOrder = inOrder(knowledgeClient);
        semanticOrder.verify(knowledgeClient)
            .semanticMatch(query, List.of(1.0, 0.0), 10);
        semanticOrder.verify(knowledgeClient)
            .semanticMatch(expanded.query(), List.of(0.0, 1.0), 10);
        assertEquals(31L, result.candidates().get(0).get("sourceId"));
        assertEquals(32L, result.candidates().get(1).get("sourceId"));
        assertEquals(expanded.query(), result.candidates().get(1).get("expandedQuery"));
    }

    @Test
    void keepsRawQuantityQueryPrimaryAndUsesIntentRewriteAsWeightedRecall() {
        String query = "批量发起合同支持多少份同时操作？";
        QueryVariant rewrite = new QueryVariant(
            "点签 是否支持签署 批量发起合同", 0.85, "intent_rewrite", false);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(embeddingService.embed(rewrite.query())).thenReturn(List.of(0.0, 1.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(chunk(4192L, 60L, "支持同时发起10份", 0.74)));
        when(knowledgeClient.semanticMatch(rewrite.query(), List.of(0.0, 1.0), 10))
            .thenReturn(List.of(chunk(4100L, 60L, "批量发起功能介绍", 0.90)));

        RagRetrievalService.RetrievalResult result = service.retrieve(
            query, null, null, Collections.emptyMap(), List.of(rewrite), true);

        assertTrue(result.answerable());
        assertEquals(4192L, result.candidates().get(0).get("sourceId"));
        Map<String, Object> rewriteCandidate = result.candidates().stream()
            .filter(candidate -> Long.valueOf(4100L).equals(candidate.get("sourceId")))
            .findFirst().orElseThrow();
        assertEquals(0.85, rewriteCandidate.get("expansionWeight"));
        assertEquals(rewrite.query(), rewriteCandidate.get("expandedQuery"));
    }

    @Test
    void mergesExpandedSemanticScoreUsingVariantWeight() {
        String query = "企业认证材料";
        QueryVariant expanded = new QueryVariant("企业实名认证所需材料", 0.80,
            "standardized", false);
        when(queryExpansionService.expand(query, false))
            .thenReturn(List.of(QueryVariant.original(query), expanded));
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(embeddingService.embed(expanded.query())).thenReturn(List.of(0.0, 1.0));
        Map<String, Object> originalHit = chunk(33L, 10L, "企业认证材料", 0.68);
        Map<String, Object> expandedHit = chunk(33L, 10L, "企业认证材料", 0.90);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(originalHit));
        when(knowledgeClient.semanticMatch(expanded.query(), List.of(0.0, 1.0), 10))
            .thenReturn(List.of(expandedHit));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        Map<String, Object> candidate = result.candidates().get(0);
        assertEquals(0.68, candidate.get("vectorScore"));
        assertEquals(0.72, candidate.get("expandedVectorScore"));
        assertEquals(0.72, candidate.get("score"));
        assertEquals(0.80, candidate.get("expansionWeight"));
        assertEquals(expanded.query(), candidate.get("expandedQuery"));
    }

    @Test
    void originalOnlyExpansionDoesNotIssueAdditionalRecall() {
        String query = "企业认证材料";
        ReflectionTestUtils.setField(service, "bm25Enabled", true);
        when(queryExpansionService.expand(query, false))
            .thenReturn(List.of(QueryVariant.original(query)));
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(chunk(34L, 10L, "企业认证材料", 0.75)));

        service.retrieve(query);

        verify(knowledgeClient).semanticMatch(query, List.of(1.0, 0.0), 10);
        verify(knowledgeClient).bm25Match(query, 10, 0.0);
        verify(knowledgeClient).lexicalMatch(query, 10, 0.72);
        verify(embeddingService).embed(query);
    }

    @Test
    void disabledExpansionDoesNotIssueAdditionalRecall() {
        String query = "企业认证材料";
        RagRetrievalService disabledExpansion = new RagRetrievalService(
            faqMatchService, embeddingService, knowledgeClient, rerankService,
            null, structuredUnitRetrievalClient, redisUtil, new ObjectMapper(),
            new BusinessSafetyBoundaryService());
        configure(disabledExpansion);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(chunk(35L, 10L, "企业认证材料", 0.75)));

        disabledExpansion.retrieve(query);

        verify(knowledgeClient).semanticMatch(query, List.of(1.0, 0.0), 10);
        verify(knowledgeClient).lexicalMatch(query, 10, 0.72);
        verifyNoInteractions(queryExpansionService);
    }

    @Test
    void forwardsTrustedFiltersToEveryRecallChannel() {
        String query = "企业认证材料";
        Map<String, Object> rawFilters = new HashMap<>();
        rawFilters.put(" categoryId ", 9L);
        rawFilters.put("sourceType", "document");
        rawFilters.put("", "ignored");
        rawFilters.put("ignored", null);
        Map<String, Object> filters = Map.of("categoryId", 9L, "sourceType", "document");
        ReflectionTestUtils.setField(service, "bm25Enabled", true);
        when(faqMatchService.match(query, true, filters)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10, filters))
            .thenReturn(Collections.emptyList());
        when(knowledgeClient.bm25Match(query, 10, 0.0, filters))
            .thenReturn(Collections.emptyList());
        when(knowledgeClient.lexicalMatch(query, 10, 0.72, filters))
            .thenReturn(Collections.emptyList());
        when(knowledgeClient.phoneticMatch(query, 10, 0.80, filters))
            .thenReturn(Collections.emptyList());

        service.retrieve(query, rawFilters, true);

        verify(faqMatchService).match(query, true, filters);
        verify(knowledgeClient).semanticMatch(query, List.of(1.0, 0.0), 10, filters);
        verify(knowledgeClient).bm25Match(query, 10, 0.0, filters);
        verify(knowledgeClient).lexicalMatch(query, 10, 0.72, filters);
        verify(knowledgeClient).phoneticMatch(query, 10, 0.80, filters);
        verify(faqMatchService, never()).match(query, true);
        verify(knowledgeClient, never()).semanticMatch(query, List.of(1.0, 0.0), 10);
        verify(knowledgeClient, never()).bm25Match(query, 10, 0.0);
        verify(knowledgeClient, never()).lexicalMatch(query, 10, 0.72);
        verify(knowledgeClient, never()).phoneticMatch(query, 10, 0.80);
    }

    @Test
    void filteredRetrievalCannotUseUnfilteredExactKeywordMatch() {
        String query = "如何重置密码";
        Map<String, Object> filters = Map.of("categoryId", 9L);
        Map<String, Object> unfilteredExact = new HashMap<>();
        unfilteredExact.put("itemId", 7L);
        unfilteredExact.put("question", query);
        unfilteredExact.put("answer", "点击忘记密码");
        unfilteredExact.put("score", 1.0);
        unfilteredExact.put("exactMatch", true);
        unfilteredExact.put("directAnswerEnabled", true);
        lenient().when(faqMatchService.match(query, true)).thenReturn(unfilteredExact);
        when(faqMatchService.match(query, true, filters)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(false);

        RagRetrievalService.RetrievalResult result = service.retrieve(query, filters, true);

        assertFalse(result.answerable());
        assertFalse(result.directAnswer());
        verify(faqMatchService).match(query, true, filters);
        verify(faqMatchService, never()).match(query, true);
    }

    @Test
    void disabledStructuredUnitIndexDoesNotCallCandidateIndex() {
        String query = "企业认证材料";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(chunk(36L, 10L, "企业认证材料", 0.75)));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertTrue(result.structuredUnitDiagnostics().isEmpty());
        verifyNoInteractions(structuredUnitRetrievalClient);
    }

    @Test
    void shadowStructuredUnitHitsAreDiagnosticOnly() {
        String query = "企业认证材料";
        ReflectionTestUtils.setField(service, "structuredUnitIndexEnabled", true);
        ReflectionTestUtils.setField(service, "structuredUnitShadowOnly", true);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(chunk(36L, 10L, "基线企业认证材料", 0.75)));
        when(structuredUnitRetrievalClient.search(
                List.of(1.0, 0.0), 5, Collections.emptyMap()))
            .thenReturn(List.of(new StructuredUnitHit("unit-1", 0.99, List.of(99L))));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList())).thenReturn(Map.of(0, 0.95));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals(0.95, result.confidence());
        assertEquals(36L, result.citations().get(0).get("sourceId"));
        assertEquals(36L, result.candidates().get(0).get("sourceId"));
        assertEquals(1, result.structuredUnitDiagnostics().size());
        Map<String, Object> diagnostic = result.structuredUnitDiagnostics().get(0);
        assertEquals(true, diagnostic.get("diagnosticOnly"));
        assertEquals("unit-1", diagnostic.get("structuredUnitId"));
        assertEquals(List.of(99L), diagnostic.get("evidenceChunkIds"));
        assertFalse(diagnostic.containsKey("content"));
        assertFalse(diagnostic.containsKey("answer"));
        verify(structuredUnitRetrievalClient, never()).evidenceChunks(anyList(), any());
        ArgumentCaptor<List<String>> rerankDocuments = ArgumentCaptor.forClass(List.class);
        verify(rerankService).rerank(eq(query), rerankDocuments.capture());
        assertEquals(1, rerankDocuments.getValue().size());
        assertFalse(rerankDocuments.getValue().get(0).contains("unit-1"));
    }

    @Test
    void activeStructuredUnitHitReranksOnlyResolvedEvidenceContent() {
        String query = "企业认证材料";
        ReflectionTestUtils.setField(service, "structuredUnitIndexEnabled", true);
        ReflectionTestUtils.setField(service, "structuredUnitShadowOnly", false);
        ReflectionTestUtils.setField(service, "structuredUnitWeight", 0.80);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(chunk(40L, 10L, "普通认证介绍", 0.80)));
        when(structuredUnitRetrievalClient.search(
                List.of(1.0, 0.0), 5, Collections.emptyMap()))
            .thenReturn(List.of(new StructuredUnitHit("unit-2", 0.95, List.of(41L))));
        Map<String, Object> resolved = chunk(41L, 11L, "认证材料原文", 0);
        resolved.put("content", "企业认证需要提交营业执照原件。");
        resolved.put("answer", "抽取答案不得进入回答链。");
        resolved.put("fullAnswer", "抽取完整答案不得进入回答链。");
        resolved.put("structuredQa", true);
        resolved.put("directAnswerEligible", true);
        when(structuredUnitRetrievalClient.evidenceChunks(
                List.of(41L), Collections.emptyMap())).thenReturn(List.of(resolved));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList()))
            .thenReturn(Map.of(0, 0.10, 1, 0.95));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        ArgumentCaptor<List<String>> documents = ArgumentCaptor.forClass(List.class);
        verify(rerankService).rerank(eq(query), documents.capture());
        String rerankInput = String.join("\n", documents.getValue());
        assertTrue(rerankInput.contains("企业认证需要提交营业执照原件"));
        assertFalse(rerankInput.contains("抽取答案不得进入回答链"));
        assertFalse(rerankInput.contains("抽取完整答案不得进入回答链"));
        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals(41L, result.citations().get(0).get("sourceId"));
        assertTrue(result.context().contains("企业认证需要提交营业执照原件"));
        assertFalse(result.context().contains("抽取答案不得进入回答链"));
        assertEquals(true, result.candidates().get(0).get("structuredUnitEvidence"));
        assertTrue(result.structuredUnitDiagnostics().isEmpty());
    }

    @Test
    void activeStructuredUnitDropsIncompleteUnitButKeepsCompleteUnit() {
        String query = "企业认证材料";
        ReflectionTestUtils.setField(service, "structuredUnitIndexEnabled", true);
        ReflectionTestUtils.setField(service, "structuredUnitShadowOnly", false);
        ReflectionTestUtils.setField(service, "structuredUnitWeight", 0.80);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(Collections.emptyList());
        when(structuredUnitRetrievalClient.search(
                List.of(1.0, 0.0), 5, Collections.emptyMap()))
            .thenReturn(List.of(
                new StructuredUnitHit("unit-incomplete", 0.98, List.of(41L, 42L)),
                new StructuredUnitHit("unit-complete", 0.92, List.of(43L))));

        Map<String, Object> partialEvidence = chunk(41L, 11L, "不完整单元证据", 0);
        partialEvidence.put("content", "该内容不能单独进入融合。");
        Map<String, Object> completeEvidence = chunk(43L, 12L, "完整单元证据", 0);
        completeEvidence.put("content", "完整单元的全部证据均已解析。");
        when(structuredUnitRetrievalClient.evidenceChunks(
                List.of(41L, 42L, 43L), Collections.emptyMap()))
            .thenReturn(List.of(partialEvidence, completeEvidence));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals(List.of(43L), result.candidates().stream()
            .map(candidate -> candidate.get("sourceId")).toList());
        assertEquals(43L, result.citations().get(0).get("sourceId"));
        assertTrue(result.context().contains("完整单元的全部证据均已解析"));
        assertFalse(result.context().contains("该内容不能单独进入融合"));
    }

    @Test
    void activeStructuredUnitDropsWholeUnitWhenAnyEvidenceContentIsBlank() {
        String query = "企业认证材料";
        ReflectionTestUtils.setField(service, "structuredUnitIndexEnabled", true);
        ReflectionTestUtils.setField(service, "structuredUnitShadowOnly", false);
        ReflectionTestUtils.setField(service, "structuredUnitWeight", 0.80);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(Collections.emptyList());
        when(structuredUnitRetrievalClient.search(
                List.of(1.0, 0.0), 5, Collections.emptyMap()))
            .thenReturn(List.of(
                new StructuredUnitHit("unit-blank", 0.98, List.of(51L, 52L))));

        Map<String, Object> validEvidence = chunk(51L, 11L, "有效证据", 0);
        validEvidence.put("content", "即使本条有效也不能单独进入融合。");
        Map<String, Object> blankEvidence = chunk(52L, 11L, "空证据", 0);
        blankEvidence.put("content", "   ");
        when(structuredUnitRetrievalClient.evidenceChunks(
                List.of(51L, 52L), Collections.emptyMap()))
            .thenReturn(List.of(validEvidence, blankEvidence));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertFalse(result.answerable());
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.citations().isEmpty());
    }

    @Test
    void mediumRerankConfidenceUsesCitedRag() {
        String query = "企业合同如何归档";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        Map<String, Object> archive = chunk(3L, 10L, "合同归档流程", 0.49);
        Map<String, Object> generic = chunk(4L, 10L, "合同管理", 0.47);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(archive, generic));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList())).thenReturn(Map.of(0, 0.78, 1, 0.70));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals("rag", result.decision());
        assertEquals(0.78, result.confidence());
        assertEquals("MEDIUM", result.rerankConfidenceTier());
        assertEquals(0.70, result.candidates().get(0).get("rerankSecondScore"));
        assertEquals(0.08, result.candidates().get(0).get("rerankScoreGap"));
        assertEquals("合同归档流程", result.citations().get(0).get("title"));
    }

    @Test
    void structuredQaRerankUsesStandardQuestionAndAcceptsRealisticNarrowGap() {
        String query = "点签是做什么的？";
        String competingQuestion = "电子合同是什么？";
        ReflectionTestUtils.setField(service, "rerankHighMinScore", 0.65);
        ReflectionTestUtils.setField(service, "rerankHighMinGap", 0.08);
        ReflectionTestUtils.setField(service, "rerankMediumMinScore", 0.40);
        ReflectionTestUtils.setField(service, "rerankMediumMinGap", -0.10);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> correct = structuredQa(4589L, 61L, query,
            "点签是电子合同平台，可在线发起、签署和管理合同。", 0.678,
            false, false);
        correct.remove("question");
        correct.put("sectionPath", query);
        correct.put("title", "【内部】点签SaaS客户问答库.docx");
        Map<String, Object> competitor = structuredQa(4590L, 61L, competingQuestion,
            "电子合同是以电子形式订立的合同。", 0.650, false, false);
        competitor.remove("question");
        competitor.put("sectionPath", competingQuestion);
        competitor.put("title", "【内部】点签SaaS客户问答库.docx");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(correct, competitor));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList())).thenAnswer(invocation -> {
            List<String> documents = invocation.getArgument(1);
            double correctScore = documents.get(0).startsWith(query + "\n")
                ? 0.765625 : 0.636719;
            return Map.of(0, correctScore, 1, 0.75390625);
        });

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        ArgumentCaptor<List<String>> documents = ArgumentCaptor.forClass(List.class);
        verify(rerankService).rerank(eq(query), documents.capture());
        assertTrue(documents.getValue().get(0).startsWith(query + "\n"));
        assertTrue(documents.getValue().get(1).startsWith(competingQuestion + "\n"));
        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals("rag", result.decision());
        assertEquals("MEDIUM", result.rerankConfidenceTier());
        assertEquals(0.766, result.confidence());
        assertEquals(0.753906, result.candidates().get(0).get("rerankSecondScore"));
        assertEquals(0.011719, result.candidates().get(0).get("rerankScoreGap"));
        assertEquals(4589L, result.citations().get(0).get("sourceId"));
        assertTrue(result.context().contains("点签是电子合同平台"));
    }

    @Test
    void lowRerankAbsoluteScoreDoesNotEnterRag() {
        String query = "企业合同如何归档";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(
                chunk(3L, 10L, "合同归档流程", 0.79),
                chunk(4L, 10L, "合同管理", 0.77)));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList())).thenReturn(Map.of(0, 0.64, 1, 0.10));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertFalse(result.answerable());
        assertEquals("no_answer", result.decision());
        assertEquals("LOW", result.rerankConfidenceTier());
        assertTrue(result.citations().isEmpty());
    }

    @Test
    void lowRerankGapDoesNotEnterRag() {
        String query = "企业合同如何归档";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(
                chunk(3L, 10L, "合同归档流程", 0.79),
                chunk(4L, 10L, "合同管理", 0.77)));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList())).thenReturn(Map.of(0, 0.95, 1, 0.94));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertFalse(result.answerable());
        assertEquals("LOW", result.rerankConfidenceTier());
        assertEquals(0.01, result.candidates().get(0).get("rerankScoreGap"));
    }

    @Test
    void failedRerankResponseKeepsLegacyAcceptance() {
        String query = "企业合同如何归档";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(chunk(3L, 10L, "合同归档流程", 0.79)));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList())).thenReturn(Collections.emptyMap());

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals("rag", result.decision());
        assertEquals(null, result.rerankConfidenceTier());
        assertEquals("合同归档流程", result.citations().get(0).get("title"));
        assertEquals(false, result.rerankDiagnostics().get("applied"));
        assertEquals("partial_response", result.rerankDiagnostics().get("failureReason"));
        assertEquals("fused", result.rerankDiagnostics().get("scoreSource"));
    }

    @Test
    void fallsBackToPhoneticMatchForHomophoneHeavyInput() {
        String query = "尼门孩耀手废，憋人在家豆免废地";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(Collections.emptyList());
        Map<String, Object> phonetic = chunk(9L, 4L, "产品问答", 0.86);
        phonetic.put("phoneticScore", 0.86);
        phonetic.put("matchMode", "phonetic");
        when(knowledgeClient.phoneticMatch(query, 10, 0.80)).thenReturn(List.of(phonetic));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals("rag", result.decision());
        assertEquals(0.86, result.confidence());
        assertEquals("phonetic", result.candidates().get(0).get("matchMode"));
    }

    @Test
    void fallsBackToLexicalMatchWhenEmbeddingCallFails() {
        String query = "企业认证怎么认证？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(Collections.emptyList());
        Map<String, Object> lexical = chunk(7L, 20L, "点签问答库", 0.75);
        lexical.put("lexicalScore", 0.75);
        lexical.put("matchMode", "lexical");
        when(knowledgeClient.lexicalMatch(query, 10, 0.72)).thenReturn(List.of(lexical));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals("rag", result.decision());
        assertEquals(0.75, result.confidence());
        assertEquals(0.0, result.candidates().get(0).get("vectorScore"));
        assertEquals(0.75, result.candidates().get(0).get("lexicalScore"));
        assertEquals("lexical", result.candidates().get(0).get("matchMode"));
    }

    @Test
    void usesProductIntroductionAliasWhenSemanticEmbeddingFails() {
        String query = "介绍一下你们公司的产品";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(Collections.emptyList());
        when(knowledgeClient.lexicalMatch(query, 10, 0.72))
            .thenReturn(Collections.emptyList());
        Map<String, Object> product = chunk(8L, 20L, "点签产品介绍", 1.0);
        product.put("content", "点签电子合同产品介绍\n点签提供电子签约全生命周期服务。");
        product.put("lexicalScore", 1.0);
        product.put("matchMode", "lexical");
        when(knowledgeClient.lexicalMatch("产品介绍", 10, 0.72))
            .thenReturn(List.of(product));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals("rag", result.decision());
        assertEquals(1.0, result.confidence());
        assertEquals("点签产品介绍", result.citations().get(0).get("title"));
    }

    @Test
    void expandsDianqianLoginQuestionToSupportedAccessChannels() {
        String query = "点签电子合同 企业怎么登录？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(false);
        when(knowledgeClient.lexicalMatch(query, 10, 0.72))
            .thenReturn(Collections.emptyList());
        Map<String, Object> access = chunk(76L, 26L, "点签使用入口", 1.0);
        access.put("content", "点签可以在哪里使用？微信、PC网页端、钉钉和企业微信。");
        access.put("lexicalScore", 1.0);
        access.put("matchMode", "lexical");
        when(knowledgeClient.lexicalMatch("点签可以在哪里使用", 10, 0.72))
            .thenReturn(List.of(access));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals("点签使用入口", result.citations().get(0).get("title"));
        assertTrue(result.context().contains("微信、PC网页端、钉钉和企业微信"));
    }

    @Test
    void expandsDianqianWebsiteLinkQuestionWithoutDependingOnWhitespace() {
        String query = "给我点签官网的链接";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(false);
        when(knowledgeClient.lexicalMatch(query, 10, 0.72))
            .thenReturn(Collections.emptyList());
        Map<String, Object> access = chunk(77L, 26L, "点签使用入口", 1.0);
        access.put("content", "点签可以在哪里使用？\n"
            + "PC网页端：https://ding.fs-signature.com/pc/");
        access.put("lexicalScore", 1.0);
        access.put("matchMode", "lexical");
        when(knowledgeClient.lexicalMatch("点签可以在哪里使用", 10, 0.72))
            .thenReturn(List.of(access));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals("点签使用入口", result.citations().get(0).get("title"));
        assertTrue(result.context().contains("https://ding.fs-signature.com/pc/"));
    }

    @Test
    void usesOcrTextForSemanticRetrievalWithoutChangingKeywordQuery() {
        when(faqMatchService.match("这个怎么处理", true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(contains("订单状态：支付失败")))
            .thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(contains("订单状态：支付失败"),
                eq(List.of(1.0, 0.0)), eq(10)))
            .thenReturn(List.of(chunk(9L, 4L, "支付故障手册", 0.74)));

        RagRetrievalService.RetrievalResult result = service.retrieve(
            "这个怎么处理", "订单状态：支付失败", true);

        assertTrue(result.answerable());
        assertEquals("rag", result.decision());
    }

    @Test
    void usesConversationContextOnlyForSemanticSearchAndAvoidsDirectAnswer() {
        Map<String, Object> keyword = new HashMap<>();
        keyword.put("itemId", 7L);
        keyword.put("question", "合同到期后还能使用吗");
        keyword.put("answer", "已签合同仍可查看，未使用额度不能继续发起合同");
        keyword.put("score", 1.0);
        when(faqMatchService.match("它到期后还能用吗？", true)).thenReturn(keyword);
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(contains("相关对话上下文")))
            .thenReturn(List.of(1.0, 0.0));
        Map<String, Object> semantic = new HashMap<>(keyword);
        semantic.put("type", "item");
        semantic.put("similarity", 0.95);
        when(knowledgeClient.semanticMatch(contains("专业版电子合同套餐"),
                eq(List.of(1.0, 0.0)), eq(10)))
            .thenReturn(List.of(semantic));

        RagRetrievalService.RetrievalResult result = service.retrieve(
            "它到期后还能用吗？", "用户: 我想了解专业版电子合同套餐", null, true);

        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals("rag", result.decision());
    }

    @Test
    void suppressesMembershipEvidenceForContractSigningFollowUp() {
        String query = "企业怎么签合同";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(contains(query))).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> membership = chunk(81L, 31L, "企业会员说明", 0.91);
        membership.put("content", "点签会员仅开放个人会员类型，无企业会员通道。");
        Map<String, Object> signing = chunk(82L, 32L, "企业电子合同签署", 0.76);
        signing.put("content", "企业完成认证后，可以发起和签署电子合同。");
        when(knowledgeClient.semanticMatch(contains(query), eq(List.of(1.0, 0.0)), eq(10)))
            .thenReturn(List.of(membership, signing));

        RagRetrievalService.RetrievalResult result = service.retrieve(
            query, "用户: 我要怎么签合同", null, true);

        assertTrue(result.answerable());
        assertEquals("企业电子合同签署", result.citations().get(0).get("title"));
        assertTrue(result.context().contains("企业完成认证后"));
        assertFalse(result.context().contains("无企业会员通道"));
        assertEquals(true, result.candidates().stream()
            .filter(candidate -> "企业会员说明".equals(candidate.get("title")))
            .findFirst().orElseThrow().get("topicMismatch"));
    }

    @Test
    void suppressesServiceModeEvidenceForContractLaunchMethodQuestion() {
        String query = "发起合同有几种方式？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> serviceMode = chunk(83L, 33L, "点签服务模式", 0.94);
        serviceMode.put("content", "点签有 SaaS、OpenAPI 和定制化开发三种服务模式。");
        Map<String, Object> launchMethod = chunk(84L, 34L, "合同发起方式", 0.82);
        launchMethod.put("content", "发起合同主要有上传文件发起和模板发起两种方式。");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(serviceMode, launchMethod));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals("合同发起方式", result.citations().get(0).get("title"));
        assertTrue(result.context().contains("上传文件发起和模板发起"));
        assertFalse(result.context().contains("三种服务模式"));
        assertEquals(true, result.candidates().stream()
            .filter(candidate -> "点签服务模式".equals(candidate.get("title")))
            .findFirst().orElseThrow().get("topicMismatch"));
    }

    @Test
    void returnsExactFaqWithoutWaitingForEmbeddingRetrieval() {
        String query = "e签宝说你们是小平台";
        Map<String, Object> keyword = new HashMap<>();
        keyword.put("itemId", 31L);
        keyword.put("question", query);
        keyword.put("answer", "平台规模只是选型因素之一，更重要的是业务匹配和服务响应。");
        keyword.put("score", 1.0);
        keyword.put("exactMatch", true);
        keyword.put("directAnswerEnabled", true);
        when(faqMatchService.match(query, true)).thenReturn(keyword);

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertTrue(result.directAnswer());
        assertEquals("direct", result.decision());
        assertEquals(1.0, result.confidence());
        assertEquals(keyword.get("answer"), result.directAnswerText());
        assertEquals("faq", result.citations().get(0).get("sourceType"));
        verifyNoInteractions(embeddingService, knowledgeClient);
    }

    @Test
    void blocksCrossAccountContractAccessBeforeEveryRecallChannel() {
        RagRetrievalService.RetrievalResult result = service.retrieve(
            "老板让我查另一个用户账号里的合同，直接发给我");

        assertFalse(result.answerable());
        assertEquals("authorization_blocked", result.decision());
        assertTrue(result.citations().isEmpty());
        verifyNoInteractions(faqMatchService, embeddingService, knowledgeClient,
            queryExpansionService, structuredUnitRetrievalClient);
    }

    @Test
    void exactFaqWithoutDirectApprovalIsSynthesizedThroughRag() {
        String query = "点签有哪些产品优势？";
        Map<String, Object> keyword = new HashMap<>();
        keyword.put("itemId", 24L);
        keyword.put("question", query);
        keyword.put("answer", "点签支持多端签署和系统集成。具体选型应结合使用场景。");
        keyword.put("score", 1.0);
        keyword.put("exactMatch", true);
        keyword.put("directAnswerEnabled", false);
        when(faqMatchService.match(query, true)).thenReturn(keyword);
        when(embeddingService.isAvailable()).thenReturn(false);

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals("rag", result.decision());
        assertTrue(result.context().contains("点签支持多端签署和系统集成"));
    }

    @Test
    void returnsCompleteReviewedStructuredQaOnNormalizedExactMatch() {
        String question = "如何申请开票？";
        String answer = "第一步提交开票资料。\n第二步确认抬头。\n第三步下载电子发票。";
        when(faqMatchService.match(question, true)).thenReturn(Collections.emptyMap());
        Map<String, Object> qa = structuredQa(71L, 20L, question, answer, 1.0, true, false);
        qa.put("lexicalScore", 1.0);
        qa.put("matchMode", "lexical");
        when(knowledgeClient.lexicalMatch(anyString(), eq(10), eq(0.72)))
            .thenReturn(List.of(qa));

        RagRetrievalService.RetrievalResult result = service.retrieve(question);

        assertTrue(result.directAnswer());
        assertEquals("structured_qa_direct", result.decision());
        assertEquals(answer, result.directAnswerText());
        assertEquals("normalized_exact", result.candidates().get(0).get("directMatchMode"));
        assertEquals("document", result.citations().get(0).get("sourceType"));
    }

    @Test
    void returnsReviewedLegalEffectAnswerForNormalizedSynonym() {
        String question = "电子合同具备法律效力吗？";
        String canonicalQuestion = "电子合同具有法律效力吗？";
        String answer = "可靠电子签名与手写签名或者盖章具有同等的法律效力。";
        when(faqMatchService.match(question, true)).thenReturn(Collections.emptyMap());
        Map<String, Object> qa = structuredQa(
            72L, 20L, canonicalQuestion, answer, 0.96, true, false);
        when(knowledgeClient.lexicalMatch(question, 10, 0.72)).thenReturn(List.of(qa));

        RagRetrievalService.RetrievalResult result = service.retrieve(question);

        assertTrue(result.directAnswer());
        assertEquals("structured_qa_direct", result.decision());
        assertEquals(answer, result.directAnswerText());
        verify(embeddingService, never()).isAvailable();
        verify(rerankService, never()).rerank(anyString(), anyList());
    }

    @Test
    void reviewedExactStructuredQaBypassesLowSemanticScoreAndUsesSectionPath() {
        String question = "批量发起合同支持多少份同时操作？";
        String answer = "支持同时发起10份";
        when(faqMatchService.match(question, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(question)).thenReturn(List.of(1.0, 0.0));
        Map<String, Object> qa = structuredQa(4608L, 61L, question,
            answer, 0.384, true, false);
        qa.remove("question");
        qa.put("sectionPath", question);
        when(knowledgeClient.semanticMatch(question, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(qa));

        RagRetrievalService.RetrievalResult result = service.retrieve(question);

        assertTrue(result.answerable());
        assertTrue(result.directAnswer());
        assertEquals("structured_qa_direct", result.decision());
        assertEquals(answer, result.directAnswerText());
        assertEquals("normalized_exact", result.candidates().get(0).get("directMatchMode"));
    }

    @Test
    void usesHighConfidenceRerankForStructuredQaVariant() {
        String query = "公司发票要怎么弄";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        Map<String, Object> qa = structuredQa(71L, 20L, "如何申请开票？",
            "提交资料并确认抬头后下载发票。", 0.80, true, false);
        Map<String, Object> other = chunk(72L, 21L, "合同下载", 0.78);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(qa, other));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList())).thenReturn(Map.of(0, 0.95, 1, 0.20));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.directAnswer());
        assertEquals("structured_qa_direct", result.decision());
        assertEquals("rerank_high_confidence", result.candidates().get(0).get("directMatchMode"));
    }

    @Test
    void fallsBackToRagWhenStructuredQaHasConflict() {
        String question = "如何申请开票？";
        when(faqMatchService.match(question, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(false);
        Map<String, Object> qa = structuredQa(71L, 20L, question,
            "提交开票资料。", 1.0, false, true);
        qa.put("lexicalScore", 1.0);
        qa.put("matchMode", "lexical");
        when(knowledgeClient.lexicalMatch(anyString(), eq(10), eq(0.72)))
            .thenReturn(List.of(qa));

        RagRetrievalService.RetrievalResult result = service.retrieve(question);

        assertFalse(result.directAnswer());
        assertEquals("rag", result.decision());
        assertTrue(result.context().contains("提交开票资料"));
    }

    @Test
    void exactReviewedCanonicalQaWinsOverDisabledDuplicate() {
        String question = "如何申请开票？";
        when(faqMatchService.match(question, true)).thenReturn(Collections.emptyMap());
        Map<String, Object> disabled = structuredQa(70L, 19L, question,
            "旧答案。", 1.0, false, false);
        Map<String, Object> canonical = structuredQa(71L, 20L, question,
            "当前标准答案。", 1.0, true, false);
        for (Map<String, Object> candidate : List.of(disabled, canonical)) {
            candidate.put("lexicalScore", 1.0);
            candidate.put("matchMode", "lexical");
        }
        when(knowledgeClient.lexicalMatch(anyString(), eq(10), eq(0.72)))
            .thenReturn(List.of(disabled, canonical));

        RagRetrievalService.RetrievalResult result = service.retrieve(question);

        assertTrue(result.directAnswer());
        assertEquals("当前标准答案。", result.directAnswerText());
        assertEquals(71L, result.citations().get(0).get("sourceId"));
    }

    @Test
    void recallsResponseEvidenceForQuestionsThatSaySolveInsteadOfRespond() {
        String query = "你们保证所有问题一小时解决吗？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(false);
        Map<String, Object> evidence = structuredQa(91L, 50L,
            "飞晟科技作为翔晟子公司，在海南有哪些本地化服务优势？",
            "远程问题1小时内响应。", 0.8, false, false);
        evidence.put("lexicalScore", 0.8);
        evidence.put("matchMode", "lexical");
        when(knowledgeClient.lexicalMatch(anyString(), eq(10), eq(0.72)))
            .thenAnswer(invocation -> "1小时内响应".equals(invocation.getArgument(0))
                ? List.of(evidence) : Collections.emptyList());

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertTrue(result.context().contains("远程问题1小时内响应"));
        verify(knowledgeClient).lexicalMatch("1小时内响应", 10, 0.72);
    }

    @Test
    void recoversUniqueReviewedAnswerFactWhenRerankRejectsContradictionEvidence() {
        String query = "你们是不是保证所有问题一小时内解决？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(false);
        Map<String, Object> evidence = structuredQa(91L, 50L,
            "飞晟科技作为翔晟子公司，在海南有哪些本地化服务优势？",
            "远程问题1小时内响应，不等于1小时内解决。", 1.0, false, false);
        evidence.put("lexicalScore", 1.0);
        evidence.put("matchMode", "lexical");
        when(knowledgeClient.lexicalMatch(anyString(), eq(10), eq(0.72)))
            .thenAnswer(invocation -> "1小时内响应".equals(invocation.getArgument(0))
                ? List.of(evidence) : Collections.emptyList());
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList())).thenReturn(Map.of(0, 0.04));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals("rag", result.decision());
        assertEquals(0.65, result.confidence());
        assertEquals("STRUCTURED_ANSWER_FACT_FALLBACK",
            result.decisionDiagnostics().get("reasonCode"));
        assertEquals("reviewed_structured_answer_fallback",
            result.decisionDiagnostics().get("confidenceSource"));
        assertEquals(true, result.candidates().get(0)
            .get("structuredAnswerFallbackAccepted"));
        assertEquals("reviewed_structured_answer_fallback",
            result.candidates().get(0).get("selectionReason"));
        assertTrue(result.context().contains("远程问题1小时内响应"));
    }

    @Test
    void keepsNoAnswerWhenReviewedAnswerFactFallbackIsAmbiguous() {
        String query = "你们是不是保证所有问题一小时内解决？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(false);
        Map<String, Object> first = structuredQa(91L, 50L, "本地化服务优势",
            "远程问题1小时内响应。", 1.0, false, false);
        Map<String, Object> second = structuredQa(92L, 51L, "技术支持承诺",
            "所有问题1小时内解决。", 1.0, false, false);
        for (Map<String, Object> candidate : List.of(first, second)) {
            candidate.put("lexicalScore", 1.0);
            candidate.put("matchMode", "lexical");
        }
        when(knowledgeClient.lexicalMatch(anyString(), eq(10), eq(0.72)))
            .thenAnswer(invocation -> "1小时内响应".equals(invocation.getArgument(0))
                ? List.of(first, second) : Collections.emptyList());
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList()))
            .thenReturn(Map.of(0, 0.04, 1, 0.03));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertFalse(result.answerable());
        assertEquals("RERANK_LOW_CONFIDENCE",
            result.decisionDiagnostics().get("reasonCode"));
    }

    @Test
    void keepsNoAnswerWhenReviewedAnswerDoesNotCoverRequestedConcreteFact() {
        String query = "你们是不是保证所有问题三小时内解决？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(false);
        Map<String, Object> evidence = structuredQa(91L, 50L, "本地化服务优势",
            "远程问题1小时内响应。", 1.0, false, false);
        evidence.put("lexicalScore", 1.0);
        evidence.put("matchMode", "lexical");
        when(knowledgeClient.lexicalMatch(anyString(), eq(10), eq(0.72)))
            .thenAnswer(invocation -> "响应".equals(invocation.getArgument(0))
                ? List.of(evidence) : Collections.emptyList());
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList())).thenReturn(Map.of(0, 0.04));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertFalse(result.answerable());
        assertEquals("RERANK_LOW_CONFIDENCE",
            result.decisionDiagnostics().get("reasonCode"));
    }

    @Test
    void exactLexicalAliasBeatsGenericMultiChannelMatches() {
        String query = "远程问题多久响应？";
        ReflectionTestUtils.setField(service, "bm25Enabled", true);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> hotline = chunk(201L, 50L, "客服热线工作时间", 0.59);
        Map<String, Object> callback = chunk(202L, 50L, "客户问题回访", 0.56);
        Map<String, Object> slow = chunk(203L, 50L, "小程序加载缓慢", 0.54);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(hotline, callback, slow));

        Map<String, Object> evidence = chunk(91L, 50L, "海南本地化服务优势", 1.0);
        evidence.put("content", "远程问题1小时内响应；上门协助服务需提前预约。");
        Map<String, Object> evidenceBm25 = new HashMap<>(evidence);
        evidenceBm25.remove("similarity");
        evidenceBm25.put("bm25Score", 25.42);
        evidenceBm25.put("matchMode", "bm25");
        Map<String, Object> hotlineBm25 = new HashMap<>(hotline);
        hotlineBm25.remove("similarity");
        hotlineBm25.put("bm25Score", 16.55);
        hotlineBm25.put("matchMode", "bm25");
        Map<String, Object> callbackBm25 = new HashMap<>(callback);
        callbackBm25.remove("similarity");
        callbackBm25.put("bm25Score", 11.32);
        callbackBm25.put("matchMode", "bm25");
        Map<String, Object> slowBm25 = new HashMap<>(slow);
        slowBm25.remove("similarity");
        slowBm25.put("bm25Score", 11.04);
        slowBm25.put("matchMode", "bm25");
        when(knowledgeClient.bm25Match(query, 10, 0.0))
            .thenReturn(List.of(evidenceBm25, hotlineBm25, callbackBm25, slowBm25));

        Map<String, Object> exactLexical = new HashMap<>(evidence);
        exactLexical.put("lexicalScore", 1.0);
        exactLexical.put("similarity", 1.0);
        exactLexical.put("matchMode", "lexical");
        when(knowledgeClient.lexicalMatch(query, 10, 0.72))
            .thenReturn(Collections.emptyList());
        when(knowledgeClient.lexicalMatch("远程问题", 10, 0.72))
            .thenReturn(List.of(exactLexical));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals(91L, result.citations().get(0).get("sourceId"));
        assertEquals(1.0, result.candidates().get(0).get("lexicalScore"));
        assertTrue(result.context().contains("远程问题1小时内响应"));
    }

    @Test
    void fallsBackToRagWhenRerankCandidatesAreTooClose() {
        String query = "公司发票要怎么弄";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        Map<String, Object> qa = structuredQa(71L, 20L, "如何申请开票？",
            "提交资料并确认抬头后下载发票。", 0.80, true, false);
        Map<String, Object> other = chunk(72L, 21L, "发票设置", 0.78);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(qa, other));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList())).thenReturn(Map.of(0, 0.95, 1, 0.91));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertFalse(result.directAnswer());
        assertEquals("rag", result.decision());
    }

    @Test
    void addsApprovedNeighborChunksWithoutChangingAnchorConfidence() {
        String query = "企业认证怎么操作";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        Map<String, Object> anchor = chunk(91L, 8L, "企业认证操作", 0.81);
        anchor.put("chunkIndex", 4);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(anchor));
        anchor.put("sectionPath", "企业服务 > 企业认证");
        when(knowledgeClient.neighborChunks(
                8L, 4, 1, "企业服务 > 企业认证")).thenReturn(List.of(
            Map.of(
                "type", "chunk",
                "chunkId", 90L,
                "sourceId", 90L,
                "documentId", 8L,
                "chunkIndex", 3,
                "sectionPath", "企业服务 > 企业认证",
                "content", "提交企业认证材料。"),
            Map.of(
                "type", "chunk",
                "chunkId", 92L,
                "sourceId", 92L,
                "documentId", 8L,
                "chunkIndex", 5,
                "sectionPath", "企业服务 > 企业认证",
                "content", "认证通过后可以创建企业印章。")));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertEquals(0.81, result.confidence());
        assertEquals(3, result.citations().size());
        assertEquals(List.of(3, 4, 5), result.citations().stream()
            .map(value -> value.get("chunkIndex")).toList());
        assertEquals("企业认证操作", result.citations().get(2).get("title"));
        assertEquals(true, result.citations().get(2).get("sourceType").equals("document"));
        assertTrue(result.context().indexOf("提交企业认证材料")
            < result.context().indexOf("员工每年享有五天年假"));
        assertTrue(result.context().contains("认证通过后可以创建企业印章"));
        assertTrue(result.context().contains("章节：企业服务 > 企业认证"));
    }

    @Test
    void doesNotMixDifferentStructuredQaNeighborIntoForeignAuthenticationContext() {
        String query = "foreign enterprise authentication";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> foreign = structuredQa(201L, 42L, query,
            "bank-card or manual review", 0.86, false, false);
        foreign.put("chunkIndex", 10);
        foreign.put("sectionPath", "authentication");
        foreign.put("qaGroupKey", "foreign-auth");
        foreign.put("content", "bank-card or manual review");
        Map<String, Object> genericPayment = structuredQa(202L, 42L,
            "enterprise authentication payment", "public-account one-cent transfer",
            0.40, false, false);
        genericPayment.put("chunkIndex", 9);
        genericPayment.put("sectionPath", "authentication");
        genericPayment.put("qaGroupKey", "general-auth");
        genericPayment.put("content", "public-account one-cent transfer");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(foreign));
        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertTrue(result.context().contains("bank-card or manual review"));
        assertFalse(result.context().contains("public-account one-cent transfer"));
        assertEquals(1, result.citations().size());
        verify(knowledgeClient, never()).neighborChunks(42L, 10, 1, "authentication");
    }

    @Test
    void keepsOneAnchorPerStructuredQaGroupAndSkipsRedundantNeighbors() {
        String query = "点签有什么功能？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> first = structuredQa(401L, 60L, "点签有什么功能？",
            "点签主要包含印章、权限和合同管理功能。", 0.86, false, false);
        first.put("qaGroupKey", "feature-group");
        first.put("chunkIndex", 10);
        first.put("sectionPath", "产品功能");
        Map<String, Object> duplicate = structuredQa(402L, 60L, "点签有什么功能？",
            "点签主要包含印章、权限和合同管理功能。", 0.85, false, false);
        duplicate.put("qaGroupKey", "feature-group");
        duplicate.put("chunkIndex", 11);
        duplicate.put("sectionPath", "产品功能");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(first, duplicate));
        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals(1, result.citations().size());
        assertEquals(401L, result.citations().get(0).get("sourceId"));
        verify(knowledgeClient, never()).neighborChunks(60L, 10, 1, "产品功能");
    }

    @Test
    void exactStructuredQuestionExcludesSimilarQaCandidatesFromContext() {
        String query = "客户是外籍人士，没有中国大陆手机号，能完成企业认证吗？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> foreign = structuredQa(301L, 53L, query,
            "国际护照可使用银行卡认证或人工审核。", 0.70, false, false);
        foreign.put("qaGroupKey", "foreign-auth");
        Map<String, Object> generic = structuredQa(302L, 53L, "企业怎么认证？",
            "管理员先完成个人认证。", 0.90, false, false);
        generic.put("qaGroupKey", "generic-enterprise-auth");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(foreign, generic));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals(1, result.citations().size());
        assertEquals(301L, result.citations().get(0).get("sourceId"));
        assertTrue(result.context().contains("国际护照可使用银行卡认证或人工审核"));
        assertFalse(result.context().contains("管理员先完成个人认证"));
        assertTrue(result.context().contains("不得自行假设客户属于其中某一行"));
        assertTrue(result.context().contains("不得补充事实未明确说明的手机号归属"));
    }

    @Test
    void embeddedStandardQuestionExcludesDifferentStructuredAnswersFromContext() {
        String query = "点签电子合同主要包含的7大功能";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> features = structuredQa(351L, 53L,
            "请问您是否已经添加点签应用？",
            "点签电子合同主要包含的7大功能：印章、身份、权限、存储、多公司、模板和提醒。",
            0.86, false, false);
        features.put("qaGroupKey", "product-features");
        Map<String, Object> introduction = structuredQa(352L, 53L,
            "电子合同可以帮我实现什么？",
            "电子合同可以帮助企业在线签署和远程协同。", 0.84, false, false);
        introduction.put("qaGroupKey", "product-introduction");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(features, introduction));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals(1, result.citations().size());
        assertEquals(351L, result.citations().get(0).get("sourceId"));
        assertTrue(result.context().contains("印章、身份、权限"));
        assertFalse(result.context().contains("远程协同"));
    }

    @Test
    void dominantStructuredQaParaphraseExcludesWeakerDifferentAnswers() {
        String query = "国际护照没有大陆手机号，可以怎么认证？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> foreign = structuredQa(321L, 53L,
            "客户是外籍人士，没有中国大陆手机号，能完成企业认证吗？",
            "国际护照：手机×，人脸×，银行卡√，人工审核√。",
            0.75, false, false);
        foreign.put("qaGroupKey", "foreign-auth");
        Map<String, Object> generic = structuredQa(322L, 53L, "企业怎么认证？",
            "管理员先完成个人认证。", 0.64, false, false);
        generic.put("qaGroupKey", "generic-enterprise-auth");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(foreign, generic));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertEquals(1, result.citations().size());
        assertTrue(result.context().contains("国际护照：手机×，人脸×"));
        assertFalse(result.context().contains("管理员先完成个人认证"));
    }

    @Test
    void closeStructuredQaParaphrasesRemainAvailableForSynthesis() {
        String query = "企业认证还有哪些方式？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> first = structuredQa(331L, 53L, "企业怎么认证？",
            "管理员认证。", 0.72, false, false);
        first.put("qaGroupKey", "enterprise-auth");
        Map<String, Object> second = structuredQa(332L, 53L,
            "法人无法人脸识别时有其他认证方式吗？",
            "可按标准答案中的其他方式认证。", 0.68, false, false);
        second.put("qaGroupKey", "alternative-auth");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(first, second));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertEquals(2, result.citations().size());
        assertTrue(result.context().contains("管理员认证"));
        assertTrue(result.context().contains("其他方式认证"));
    }

    @Test
    void recognizesDomainSynonymsAndWordOrderAsSimilarSentences() {
        double similarity = service.questionSimilarity(
            "如何进行企业认证？", "公司认证怎么做？");

        assertTrue(similarity >= 0.70, "similarity=" + similarity);
    }

    @Test
    void keepsDifferentOperationsApartWhenOnlyQuestionFormMatches() {
        double similarity = service.questionSimilarity(
            "怎么登录？", "怎么认证？");

        assertTrue(similarity < 0.60, "similarity=" + similarity);
    }

    @Test
    void promotesFocusedStructuredQaWhenTopRerankHitHasVeryLowQuestionAlignment() {
        String query = "一个账号能管理多家子公司的合同吗？";
        ReflectionTestUtils.setField(service, "rerankHighMinScore", 0.65);
        ReflectionTestUtils.setField(service, "rerankMediumMinScore", 0.40);
        ReflectionTestUtils.setField(service, "rerankMediumMinGap", -0.10);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> broad = structuredQa(5697L, 70L,
            "怎样完成点签标准签约流程？",
            "先认证，再发起合同并完成签署。", 0.82, false, false);
        broad.put("qaGroupKey", "signing-flow");
        Map<String, Object> focused = structuredQa(5609L, 70L,
            "一个账号可以认证和管理多家企业吗？",
            "可以认证多家企业，并在企业之间切换管理合同。", 0.78, false, false);
        focused.put("qaGroupKey", "multiple-enterprises");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(broad, focused));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList()))
            .thenReturn(Map.of(0, 0.718750, 1, 0.621094));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals(1, result.citations().size());
        assertEquals(5609L, result.citations().get(0).get("sourceId"));
        assertTrue(result.context().contains("认证多家企业"));
        assertFalse(result.context().contains("标准签约流程"));
        Map<String, Object> diagnostic = result.candidates().stream()
            .filter(candidate -> Long.valueOf(5609L).equals(candidate.get("sourceId")))
            .findFirst().orElseThrow();
        assertEquals(true, diagnostic.get("focusedStructuredQaPromoted"));
        assertEquals(5697L, diagnostic.get("focusedStructuredQaReplacedSourceId"));
        assertTrue(((Number) diagnostic.get("focusedStructuredQaQuestionSimilarity"))
            .doubleValue() > ((Number) diagnostic.get(
                "focusedStructuredQaPreviousQuestionSimilarity")).doubleValue());
    }

    @Test
    void keepsRerankerWinnerWhenFocusedStructuredQaScoreGapIsLarge() {
        String query = "一个账号能管理多家子公司的合同吗？";
        ReflectionTestUtils.setField(service, "rerankHighMinScore", 0.65);
        ReflectionTestUtils.setField(service, "rerankMediumMinScore", 0.40);
        ReflectionTestUtils.setField(service, "rerankMediumMinGap", -0.10);
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> broad = structuredQa(5697L, 70L,
            "怎样完成点签标准签约流程？",
            "先认证，再发起合同并完成签署。", 0.82, true, false);
        broad.put("qaGroupKey", "signing-flow");
        Map<String, Object> focused = structuredQa(5609L, 70L,
            "一个账号可以认证和管理多家企业吗？",
            "可以认证多家企业，并在企业之间切换管理合同。", 0.78, true, false);
        focused.put("qaGroupKey", "multiple-enterprises");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(broad, focused));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList()))
            .thenReturn(Map.of(0, 0.86, 1, 0.60));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertEquals(5697L, result.citations().get(0).get("sourceId"));
        assertTrue(result.candidates().stream()
            .noneMatch(candidate -> Boolean.TRUE.equals(
                candidate.get("focusedStructuredQaPromoted"))));
    }

    @Test
    void compositeQuestionKeepsEvidenceFromMultipleStructuredQaGroups() {
        String query = "外籍人士怎么认证？对公打款多久通过？";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> foreign = structuredQa(311L, 53L,
            "客户是外籍人士，没有中国大陆手机号，能完成企业认证吗？",
            "国际护照可使用银行卡认证或人工审核。", 0.86, false, false);
        foreign.put("qaGroupKey", "foreign-auth");
        Map<String, Object> payment = structuredQa(312L, 53L,
            "企业认证用对公打款，打款后多久能通过认证？",
            "收到款项后按标准答案中的时效处理。", 0.74, false, false);
        payment.put("qaGroupKey", "payment-auth");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(foreign, payment));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals(2, result.citations().size());
        assertTrue(result.context().contains("国际护照可使用银行卡认证或人工审核"));
        assertTrue(result.context().contains("收到款项后按标准答案中的时效处理"));
    }

    @Test
    void removesNeighborWhoseContentDuplicatesTheAnchor() {
        String query = "企业认证怎么操作";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        Map<String, Object> anchor = chunk(91L, 8L, "企业认证操作", 0.81);
        anchor.put("chunkIndex", 4);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(anchor));
        when(knowledgeClient.neighborChunks(8L, 4, 1, "")).thenReturn(List.of(Map.of(
            "type", "chunk",
            "chunkId", 92L,
            "sourceId", 92L,
            "documentId", 8L,
            "chunkIndex", 5,
            "content", "员工每年享有五天年假")));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertEquals(1, result.citations().size());
    }

    @Test
    void sendsAnInexactHighKeywordMatchThroughRagInsteadOfReturningItDirectly() {
        String query = "你们公司的点签主要是做什么的？";
        Map<String, Object> keyword = new HashMap<>();
        keyword.put("itemId", 15L);
        keyword.put("question", "你们公司主要是做什么的？");
        keyword.put("answer", "公司提供多种软件产品。点签是电子合同产品。");
        keyword.put("score", 0.9);
        keyword.put("exactMatch", false);
        when(faqMatchService.match(query, true)).thenReturn(keyword);
        when(embeddingService.isAvailable()).thenReturn(false);
        when(knowledgeClient.lexicalMatch(query, 10, 0.72))
            .thenReturn(Collections.emptyList());

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals("rag", result.decision());
        assertEquals(false, result.candidates().get(0).get("exactMatch"));
        assertTrue(result.context().contains("只提取与所问对象和意图直接相关的内容"));
    }

    @Test
    void exactLexicalChunkReranksAboveGenericSemanticCompetitorMatches() {
        String query = "e签宝说你们是小平台";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> generic = chunk(100L, 20L, "竞品通用问答", 0.668);
        Map<String, Object> headquarters = chunk(599L, 21L, "总部介绍", 0.645);
        Map<String, Object> exactSemantic = chunk(566L, 20L, "点签SaaS客户问答库", 0.620);
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(generic, headquarters, exactSemantic));

        Map<String, Object> exactLexical = new HashMap<>(exactSemantic);
        exactLexical.put("content", query + "\n平台规模只是选型因素之一。");
        exactLexical.put("lexicalScore", 1.0);
        exactLexical.put("similarity", 1.0);
        exactLexical.put("matchMode", "lexical");
        when(knowledgeClient.lexicalMatch(query, 10, 0.72))
            .thenReturn(List.of(exactLexical));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals(1.0, result.confidence());
        assertEquals(566L, result.citations().get(0).get("sourceId"));
        assertEquals(1.0, result.candidates().get(0).get("lexicalScore"));
        assertEquals("lexical", result.candidates().get(0).get("matchMode"));
    }

    @Test
    void keepsQualifyingImageWhenExplicitImageRequestHasHigherScoringTextMatches() {
        String query = "给我发一张点签产品图";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));

        Map<String, Object> image = chunk(104L, 24L, "点签产品介绍图.png", 0.80);
        image.put("sourceType", "image");
        image.put("previewUrl", "/api/public/knowledge-images/24?expires=1&signature=test");
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(
                chunk(101L, 21L, "点签产品介绍", 0.90),
                chunk(102L, 22L, "电子合同方案", 0.88),
                chunk(103L, 23L, "签署流程说明", 0.86),
                image));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals(0.90, result.confidence());
        assertEquals(3, result.citations().size());
        assertTrue(result.citations().stream()
            .anyMatch(citation -> "image".equals(citation.get("sourceType"))));
        assertTrue(result.citations().stream()
            .anyMatch(citation -> "点签产品介绍图.png".equals(citation.get("title"))));
    }

    @Test
    void mergesScreenshotAndKnowledgeEvidenceWithStableCitationNumbers() {
        Map<String, Object> screenshot = new HashMap<>();
        screenshot.put("ref", 1);
        screenshot.put("id", "image:12");
        screenshot.put("sourceType", "image");
        screenshot.put("title", "订单截图");
        RagRetrievalService.RetrievalResult knowledge = new RagRetrievalService.RetrievalResult(
            true, false, null,
            "【知识库依据】\n[1] 支付故障手册\n批次编号 [2024]\n请依据 [1] 回答。",
            0.74, "rag", true,
            List.of(Map.of(
                "ref", 1,
                "id", "chunk:9",
                "sourceType", "document",
                "title", "支付故障手册")),
            Collections.emptyList());

        RagRetrievalService.RetrievalResult result = service.mergeWithProvidedContext(
            knowledge, "【截图依据】\n[1] 订单状态：支付失败", List.of(screenshot));

        assertTrue(result.answerable());
        assertFalse(result.directAnswer());
        assertEquals("multimodal_rag", result.decision());
        assertEquals(2, result.citations().size());
        assertEquals(1, result.citations().get(0).get("ref"));
        assertEquals(2, result.citations().get(1).get("ref"));
        assertTrue(result.context().contains("[2] 支付故障手册"));
        assertTrue(result.context().contains("批次编号 [2024]"));
        assertTrue(result.context().contains("请依据 [1] 回答"));
    }

    @Test
    void explainsWhyCandidatesWereSelectedOrRejected() {
        String query = "年假有几天";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(
                chunk(201L, 31L, "年假制度", 0.82),
                chunk(202L, 32L, "办公制度", 0.40)));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertTrue(result.answerable());
        assertEquals("FUSED_EVIDENCE_ACCEPTED",
            result.decisionDiagnostics().get("reasonCode"));
        assertEquals(1, result.candidates().get(0).get("finalRank"));
        assertEquals(true, result.candidates().get(0).get("selectedForAnswer"));
        assertEquals("requested_fact_coverage",
            result.candidates().get(0).get("selectionReason"));
        assertEquals(false, result.candidates().get(1).get("selectedForAnswer"));
        assertTrue(((List<?>) result.candidates().get(1).get("rejectionReasons"))
            .contains("retrieval_score_below_threshold"));
    }

    @Test
    void explainsNoAnswerCausedByAmbiguousRerankScores() {
        String query = "怎么使用企业认证";
        when(faqMatchService.match(query, true)).thenReturn(Collections.emptyMap());
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(query)).thenReturn(List.of(1.0, 0.0));
        when(knowledgeClient.semanticMatch(query, List.of(1.0, 0.0), 10))
            .thenReturn(List.of(
                chunk(211L, 41L, "企业认证", 0.86),
                chunk(212L, 42L, "企业账号", 0.82)));
        when(rerankService.isAvailable()).thenReturn(true);
        when(rerankService.rerank(eq(query), anyList()))
            .thenReturn(Map.of(0, 0.70, 1, 0.69));

        RagRetrievalService.RetrievalResult result = service.retrieve(query);

        assertFalse(result.answerable());
        assertEquals("RERANK_LOW_CONFIDENCE",
            result.decisionDiagnostics().get("reasonCode"));
        assertEquals("LOW",
            result.decisionDiagnostics().get("rerankConfidenceTier"));
        assertTrue(((List<?>) result.candidates().get(0).get("rejectionReasons"))
            .contains("rerank_low_confidence"));
    }

    private Map<String, Object> chunk(Long chunkId, Long documentId, String title, double score) {
        Map<String, Object> value = new HashMap<>();
        value.put("type", "chunk");
        value.put("chunkId", chunkId);
        value.put("sourceId", chunkId);
        value.put("documentId", documentId);
        value.put("chunkIndex", 2);
        value.put("title", title);
        value.put("content", "员工每年享有五天年假");
        value.put("answer", "员工每年享有五天年假");
        value.put("similarity", score);
        return value;
    }

    private Map<String, Object> structuredQa(Long chunkId, Long documentId,
                                             String question, String answer, double score,
                                             boolean eligible, boolean conflict) {
        Map<String, Object> value = chunk(chunkId, documentId, "标准问答", score);
        value.put("structuredQa", true);
        value.put("knowledgeType", "structured_qa");
        value.put("question", question);
        value.put("answer", answer);
        value.put("fullAnswer", answer);
        value.put("qaKey", "qa-" + chunkId);
        value.put("qaVersion", 1);
        value.put("directAnswerEnabled", true);
        value.put("directAnswerEligible", eligible);
        value.put("qaConflict", conflict);
        return value;
    }
}
