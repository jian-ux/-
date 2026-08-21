package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.dto.QueryVariant;
import com.feisheng.bot.core.service.impl.AiModelServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryExpansionServiceTest {
    private AiModelServiceImpl aiModelService;
    private QueryExpansionService service;

    @BeforeEach
    void setUp() {
        aiModelService = mock(AiModelServiceImpl.class);
        service = new QueryExpansionService(aiModelService, new ObjectMapper(), 5, 160);
    }

    @Test
    void preservesOriginalAndAssignsPurposeWeights() {
        respondWith("""
            {"original_query":"电子合同有法律效力吗？","variants":[
              {"query":"电子合同是否合法有效？","purpose":"standardized"},
              {"query":"电子合同受法律保护吗？","purpose":"synonym"},
              {"query":"电子合同算不算有效合同？","purpose":"colloquial"}
            ]}
            """);

        List<QueryVariant> variants = service.expand(" 电子合同有法律效力吗？ ");

        assertEquals(4, variants.size());
        assertEquals(new QueryVariant("电子合同有法律效力吗？", 1.0, "original", true),
            variants.get(0));
        assertEquals(0.90, variants.get(1).weight());
        assertEquals("standardized", variants.get(1).purpose());
        assertFalse(variants.get(1).original());
        assertEquals(0.82, variants.get(2).weight());
        assertEquals(0.76, variants.get(3).weight());
    }

    @Test
    void capsTotalVariantsAtConfiguredMaximum() {
        service = new QueryExpansionService(aiModelService, new ObjectMapper(), 3, 160);
        respondWith("""
            {"original_query":"电子合同怎么签署？","variants":[
              {"query":"电子合同签署流程是什么？","purpose":"standardized"},
              {"query":"如何完成电子合同签署？","purpose":"synonym"},
              {"query":"电子合同咋签？","purpose":"colloquial"},
              {"query":"电子合同签署有哪些步骤？","purpose":"subquery"}
            ]}
            """);

        List<QueryVariant> variants = service.expand("电子合同怎么签署？");

        assertEquals(3, variants.size());
        assertTrue(variants.get(0).original());
    }

    @Test
    void skipsWhenCallerAlreadyHasAnExactMatch() {
        List<QueryVariant> variants = service.expand("电子合同有法律效力吗？", true);

        assertOriginalOnly(variants, "电子合同有法律效力吗？");
        assertFalse(service.shouldExpand("电子合同有法律效力吗？", true));
        verify(aiModelService, never()).chat(anyString(), anyString());
    }

    @Test
    void addsDomainSynonymsWithoutCallingModelWhenModelExpansionIsDisabled() {
        service = new QueryExpansionService(
            aiModelService, new ObjectMapper(), false, 5, 160);

        List<QueryVariant> variants = service.expand("公司认证后怎么盖章？");

        assertEquals(List.of(
            QueryVariant.original("公司认证后怎么盖章？"),
            new QueryVariant("企业认证后怎么盖章？", 0.84, "synonym", false),
            new QueryVariant("公司认证后怎么用印？", 0.84, "synonym", false)
        ), variants);
        assertTrue(service.shouldExpand("公司认证后怎么盖章？", false));
        verify(aiModelService, never()).chat(anyString(), anyString());
    }

    @Test
    void capsBuiltInVariantsAndKeepsOriginalFirst() {
        service = new QueryExpansionService(
            aiModelService, new ObjectMapper(), false, 3, 160);

        List<QueryVariant> variants = service.expand("公司认证后如何发起合同签署？");

        assertEquals(3, variants.size());
        assertTrue(variants.get(0).original());
        assertEquals("企业认证后如何发起合同签署？", variants.get(1).query());
        assertEquals("公司认证后如何发起合同签约？", variants.get(2).query());
        verify(aiModelService, never()).chat(anyString(), anyString());
    }

    @Test
    void skipsVeryLongQueries() {
        service = new QueryExpansionService(aiModelService, new ObjectMapper(), 5, 32);
        String query = "电子合同".repeat(9);

        assertOriginalOnly(service.expand(query), query);
        verify(aiModelService, never()).chat(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "查询一下我的订单",
        "订单状态怎么样",
        "快递到哪了",
        "什么时候发货",
        "FS202607170001"
    })
    void skipsQueriesHandledByBusinessTools(String query) {
        assertOriginalOnly(service.expand(query), query);
        assertFalse(service.shouldExpand(query, false));
        verify(aiModelService, never()).chat(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "```json\n{\"original_query\":\"电子合同怎么签？\",\"variants\":[]}\n```",
        "{\"original_query\":\"电子合同怎么签？\",\"variants\":[],\"extra\":true}",
        "{\"original_query\":\"别的问题\",\"variants\":[]}",
        "{\"original_query\":\"电子合同怎么签？\",\"variants\":[{\"query\":\"电子合同签署流程\",\"purpose\":\"answer\"}]}"
    })
    void fallsBackForInvalidOrNonStrictJson(String content) {
        respondWith(content);

        assertOriginalOnly(service.expand("电子合同怎么签？"), "电子合同怎么签？");
    }

    @Test
    void fallsBackWhenModelCallFails() {
        when(aiModelService.chat(anyString(), anyString()))
            .thenReturn(new ChatResponse("service unavailable", false));

        assertOriginalOnly(service.expand("电子合同怎么签？"), "电子合同怎么签？");
    }

    @Test
    void fallsBackWhenModelThrows() {
        when(aiModelService.chat(anyString(), anyString()))
            .thenThrow(new IllegalStateException("model unavailable"));

        assertOriginalOnly(service.expand("电子合同怎么签？"), "电子合同怎么签？");
    }

    @Test
    void fallsBackWhenAnyVariantDriftsToAnotherTopic() {
        respondWith("""
            {"original_query":"电子合同怎么签？","variants":[
              {"query":"电子合同签署流程是什么？","purpose":"standardized"},
              {"query":"退款什么时候到账？","purpose":"synonym"}
            ]}
            """);

        assertOriginalOnly(service.expand("电子合同怎么签？"), "电子合同怎么签？");
    }

    @Test
    void fallsBackWhenVariantFlipsNegativePolarity() {
        respondWith("""
            {"original_query":"电子合同支持离线签署吗？","variants":[
              {"query":"电子合同为什么不能离线签署？","purpose":"standardized"}
            ]}
            """);

        assertEquals(List.of(
            QueryVariant.original("电子合同支持离线签署吗？"),
            new QueryVariant("电子合同支持离线签约吗？", 0.84, "synonym", false)
        ), service.expand("电子合同支持离线签署吗？"));
    }

    @Test
    void rejectsVariantThatMovesNegationToAnotherAction() {
        respondWith("""
            {"original_query":"合同不能下载但可以查看吗？","variants":[
              {"query":"合同可以下载但不能查看吗？","purpose":"standardized"}
            ]}
            """);

        assertOriginalOnly(service.expand("合同不能下载但可以查看吗？"),
            "合同不能下载但可以查看吗？");
    }

    @Test
    void recognizesColloquialNegationWhenModelDropsIt() {
        respondWith("""
            {"original_query":"没显示下载按钮怎么办？","variants":[
              {"query":"下载按钮在哪里？","purpose":"standardized"}
            ]}
            """);

        assertOriginalOnly(service.expand("没显示下载按钮怎么办？"),
            "没显示下载按钮怎么办？");
    }

    @Test
    void acceptsEquivalentNegativeWordingForTheSameAction() {
        respondWith("""
            {"original_query":"电子合同为什么不能离线签署？","variants":[
              {"query":"电子合同为何无法离线签约？","purpose":"standardized"}
            ]}
            """);

        List<QueryVariant> variants = service.expand("电子合同为什么不能离线签署？");

        assertTrue(variants.stream().anyMatch(variant ->
            "电子合同为何无法离线签约？".equals(variant.query())));
    }

    @Test
    void keepsNeutralChoiceQuestionsNeutral() {
        respondWith("""
            {"original_query":"合同能不能下载？","variants":[
              {"query":"合同是否支持下载？","purpose":"standardized"}
            ]}
            """);

        assertTrue(service.expand("合同能不能下载？").stream().anyMatch(variant ->
            "合同是否支持下载？".equals(variant.query())));
    }

    @Test
    void fallsBackForDuplicateJsonFields() {
        respondWith("""
            {"original_query":"电子合同怎么签？",
             "original_query":"电子合同怎么签？","variants":[]}
            """);

        assertOriginalOnly(service.expand("电子合同怎么签？"), "电子合同怎么签？");
    }

    @Test
    void fallsBackWhenVariantDropsOrChangesAnIdentifier() {
        respondWith("""
            {"original_query":"PROD-2026电子合同怎么续费？","variants":[
              {"query":"PROD-2027电子合同续费流程是什么？","purpose":"standardized"}
            ]}
            """);

        assertOriginalOnly(service.expand("PROD-2026电子合同怎么续费？"),
            "PROD-2026电子合同怎么续费？");
    }

    @Test
    void removesOriginalAndDuplicateVariantsReturnedByModel() {
        respondWith("""
            {"original_query":"电子合同怎么签？","variants":[
              {"query":"电子合同怎么签？","purpose":"colloquial"},
              {"query":"电子合同签署流程是什么？","purpose":"standardized"},
              {"query":"电子合同签署流程是什么","purpose":"synonym"}
            ]}
            """);

        List<QueryVariant> variants = service.expand("电子合同怎么签？");

        assertEquals(2, variants.size());
        assertTrue(variants.get(0).original());
        assertEquals("电子合同签署流程是什么？", variants.get(1).query());
    }

    @Test
    void escapesQueryAsJsonInModelPrompt() {
        String query = "电子合同\"怎么签\\以及换行\n测试";
        respondWith("""
            {"original_query":"电子合同\\\"怎么签\\\\以及换行\\n测试","variants":[]}
            """);

        List<QueryVariant> variants = service.expand(query);

        assertOriginalOnly(variants, query);
        verify(aiModelService).chat(
            org.mockito.ArgumentMatchers.contains(
                "{\"query\":\"电子合同\\\"怎么签\\\\以及换行\\n测试\",\"max_variants\":4}"),
            anyString());
    }

    @Test
    void returnsNoVariantForBlankInput() {
        assertTrue(service.expand("  ").isEmpty());
        assertTrue(service.expand(null).isEmpty());
        verify(aiModelService, never()).chat(anyString(), anyString());
    }

    private void respondWith(String content) {
        when(aiModelService.chat(anyString(), anyString()))
            .thenReturn(new ChatResponse(content, true));
    }

    private void assertOriginalOnly(List<QueryVariant> variants, String original) {
        assertEquals(List.of(QueryVariant.original(original)), variants);
    }
}
