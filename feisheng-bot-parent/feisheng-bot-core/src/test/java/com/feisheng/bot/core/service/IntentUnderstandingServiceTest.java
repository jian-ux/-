package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.service.impl.AiModelServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentUnderstandingServiceTest {
    @Mock private AiModelServiceImpl aiModelService;

    @Test
    void returnsStandaloneKnowledgeQueryFromRecentContext() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(7L), anyMap()))
            .thenReturn(response("""
                {"route":"KNOWLEDGE","intent_code":"ACCOUNT_OPERATION",\
                "standalone_query":"点签企业账号如何登录？",\
                "entities":{"user_type":"企业"},"missing_slots":[],\
                "context_dependent":true,"confidence":0.91}
                """));

        IntentUnderstandingService.Understanding result = service.understand(
            "这个怎么操作", List.of(
                message("user", "企业账号登录失败"),
                message("ai", "请说明您看到的页面提示"),
                message("user", "这个怎么操作")), 99L);

        assertTrue(result.knowledge());
        assertEquals("ACCOUNT_OPERATION", result.intentCode());
        assertEquals("点签企业账号如何登录？", result.standaloneQuery());
        assertEquals(Map.of("user_type", "企业"), result.entities());
        assertTrue(result.contextDependent());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithExactModelJson(
            prompt.capture(), anyString(), eq(7L), argThat(schema ->
                Boolean.FALSE.equals(schema.get("additionalProperties"))
                    && ((List<?>) schema.get("required")).size() == 7));
        verify(aiModelService, never()).chatWithModel(
            anyString(), anyString(), nullable(Long.class));
        assertTrue(prompt.getValue().contains("企业账号登录失败"));
        assertEquals(1, occurrences(prompt.getValue(), "这个怎么操作"));
    }

    @Test
    void acceptsHistoryRecallAsSemanticKnowledgeRoute() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(7L), anyMap()))
            .thenReturn(response("""
                {"route":"KNOWLEDGE","intent_code":"HISTORY_RECALL",
                "standalone_query":"企业认证","entities":{},"missing_slots":[],
                "context_dependent":true,"confidence":0.95}
                """));

        IntentUnderstandingService.Understanding result = service.understand(
            "我忘记我之前是哪个认证了？", List.of(
                message("user", "怎么完成企业认证？"),
                message("ai", "企业认证有三种方式。")), null);

        assertTrue(result.knowledge());
        assertEquals("HISTORY_RECALL", result.intentCode());
        assertEquals("企业认证", result.standaloneQuery());
    }

    @Test
    void rejectsLowConfidenceResultWithoutLosingDiagnostics() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(7L), anyMap()))
            .thenReturn(response("""
                {"route":"KNOWLEDGE","intent_code":"OTHER_KNOWLEDGE",\
                "standalone_query":"点签相关问题", "entities":{},\
                "missing_slots":[],"context_dependent":false,"confidence":0.52}
                """));

        IntentUnderstandingService.Understanding result =
            service.understand("帮我看看", List.of(), null);

        assertTrue(result.attempted());
        assertFalse(result.actionable());
        assertEquals("confidence_below_threshold", result.reasonCode());
    }

    @Test
    void acceptsActionableClarificationWithControlledMissingSlot() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(7L), anyMap()))
            .thenReturn(response("""
                {"route":"CLARIFY","intent_code":"UNKNOWN",\
                "standalone_query":"", "entities":{"operation":"登录"},\
                "missing_slots":["user_type"],"context_dependent":true,\
                "confidence":0.90}
                """));

        IntentUnderstandingService.Understanding result =
            service.understand("登录不上", List.of(), null);

        assertTrue(result.actionable());
        assertEquals(IntentUnderstandingService.Route.CLARIFY, result.route());
        assertEquals(List.of("user_type"), result.missingSlots());
    }

    @Test
    void rejectsUnknownMissingSlot() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(7L), anyMap()))
            .thenReturn(response("""
                {"route":"CLARIFY","intent_code":"UNKNOWN",\
                "standalone_query":"", "entities":{},\
                "missing_slots":["secret_token"],"context_dependent":true,\
                "confidence":0.90}
                """));

        IntentUnderstandingService.Understanding result =
            service.understand("登录不上", List.of(), null);

        assertFalse(result.actionable());
        assertEquals("invalid_model_output", result.reasonCode());
    }

    @Test
    void rejectsUnexpectedModelFields() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(7L), anyMap()))
            .thenReturn(response("""
                {"route":"KNOWLEDGE","intent_code":"PRODUCT_USAGE",\
                "standalone_query":"点签怎么用", "entities":{},\
                "missing_slots":[],"context_dependent":false,"confidence":0.90,\
                "answer":"直接登录"}
                """));

        IntentUnderstandingService.Understanding result =
            service.understand("点签怎么用", List.of(), null);

        assertTrue(result.attempted());
        assertFalse(result.actionable());
        assertEquals("invalid_model_output", result.reasonCode());
    }

    @Test
    void rejectsKnowledgeResultWithMissingSlots() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(7L), anyMap()))
            .thenReturn(response("""
                {"route":"KNOWLEDGE","intent_code":"PRODUCT_USAGE",\
                "standalone_query":"点签怎么用", "entities":{},\
                "missing_slots":["operation"],"context_dependent":false,\
                "confidence":0.90}
                """));

        assertInvalid(service.understand("点签怎么用", List.of(), null));
    }

    @Test
    void rejectsClarificationWithSpecificIntent() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(7L), anyMap()))
            .thenReturn(response("""
                {"route":"CLARIFY","intent_code":"ACCOUNT_OPERATION",\
                "standalone_query":"", "entities":{},\
                "missing_slots":["user_type"],"context_dependent":true,\
                "confidence":0.90}
                """));

        assertInvalid(service.understand("登录不上", List.of(), null));
    }

    @Test
    void rejectsOutOfScopeWithMismatchedFields() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(7L), anyMap()))
            .thenReturn(response("""
                {"route":"OUT_OF_SCOPE","intent_code":"OTHER_KNOWLEDGE",\
                "standalone_query":"", "entities":{"product":"电影"},\
                "missing_slots":[],"context_dependent":false,"confidence":0.95}
                """));

        assertInvalid(service.understand("推荐一部电影", List.of(), null));
    }

    @Test
    void rejectsUnknownOrSensitiveEntityField() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(7L), anyMap()))
            .thenReturn(response("""
                {"route":"KNOWLEDGE","intent_code":"ACCOUNT_OPERATION",\
                "standalone_query":"如何登录账号", "entities":{"phone":"13800138000"},\
                "missing_slots":[],"context_dependent":false,"confidence":0.95}
                """));

        assertInvalid(service.understand("如何登录账号", List.of(), null));
    }

    @Test
    void acceptsSystemIntegrationAndReceivesReadOnlyConversationState() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(7L), anyMap()))
            .thenReturn(response("""
                {"route":"KNOWLEDGE","intent_code":"SYSTEM_INTEGRATION",\
                "standalone_query":"点签电子签章是否支持通过API集成到ERP系统？",\
                "entities":{"business_system":"ERP"},"missing_slots":[],\
                "context_dependent":true,"confidence":0.95}
                """));

        IntentUnderstandingService.Understanding result = service.understand(
            "那我们的ERP系统呢？", List.of(), null, Map.of(
                "status", "ACTIVE",
                "active_intent", "SYSTEM_INTEGRATION",
                "standalone_query", "点签电子签章是否支持通过API集成到CRM系统？",
                "entities", Map.of("business_system", "CRM")));

        assertTrue(result.knowledge());
        assertEquals("SYSTEM_INTEGRATION", result.intentCode());
        assertEquals(Map.of("business_system", "ERP"), result.entities());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(aiModelService).chatWithExactModelJson(
            prompt.capture(), anyString(), eq(7L), schema.capture());
        assertTrue(prompt.getValue().contains("conversation_state"));
        assertTrue(prompt.getValue().contains("SYSTEM_INTEGRATION"));
        Map<?, ?> properties = (Map<?, ?>) schema.getValue().get("properties");
        Map<?, ?> intentCode = (Map<?, ?>) properties.get("intent_code");
        assertTrue(((List<?>) intentCode.get("enum")).contains("SYSTEM_INTEGRATION"));
    }

    @Test
    void parsesStrictContextDecisionWithSelectedCandidateIds() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(11L), anyMap()))
                .thenReturn(response("""
                        {"relation":"FOLLOW_UP","intent":"PRODUCT_USAGE",
                         "selected_context_ids":["message:9"],"selected_memory_ids":[],
                         "task_action":"CONTINUE","task_id":"task:usage",
                         "original_requirements":["需要视频形式的教程"],
                         "resolved_query":"点签是否提供使用视频教程？",
                         "confidence":0.93,"need_large_model":false}
                        """));
        ContextCandidate candidate = new ContextCandidate(
                "message:9", "recent_message", "点签的使用教程", 24L, 9L,
                "web", "customer-1", 1D, null, null, "recent_session");
        TurnContext context = TurnContext.start("turn:24", "web", "customer-1",
                24L, 10L, "有没有视频的？", List.of(candidate));

        IntentUnderstandingService.ContextModelResult result = service.decideContext(context, 11L);

        assertTrue(result.attempted());
        assertEquals(ContextDecision.Relation.FOLLOW_UP, result.decision().relation());
        assertEquals(List.of("message:9"), result.decision().selectedContextIds());
        assertEquals(List.of("需要视频形式的教程"), result.decision().originalRequirements());
        assertEquals("点签是否提供使用视频教程？", result.decision().resolvedQuery());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(aiModelService).chatWithExactModelJson(
                prompt.capture(), anyString(), eq(11L), schema.capture());
        assertTrue(prompt.getValue().contains("有没有视频的？"));
        assertTrue(prompt.getValue().contains("message:9"));
        assertEquals(10, ((List<?>) schema.getValue().get("required")).size());
        assertEquals(Boolean.FALSE, schema.getValue().get("additionalProperties"));
    }

    @Test
    void rejectsContextDecisionWithUnexpectedFields() {
        IntentUnderstandingService service = service(true, 0.75, 4, 7L);
        when(aiModelService.chatWithExactModelJson(
                anyString(), anyString(), eq(11L), anyMap()))
                .thenReturn(response("""
                        {"relation":"NEW_TOPIC","intent":"PRODUCT_USAGE",
                         "selected_context_ids":[],"selected_memory_ids":[],
                         "task_action":"CREATE","task_id":"task:usage",
                         "original_requirements":[],"resolved_query":"点签怎么用？",
                         "confidence":0.93,"need_large_model":false,
                         "answer":"直接登录"}
                        """));
        TurnContext context = TurnContext.start("turn:1", "web", "customer-1",
                1L, 1L, "点签怎么用？", List.of());

        IntentUnderstandingService.ContextModelResult result = service.decideContext(context, 11L);

        assertTrue(result.attempted());
        assertEquals(null, result.decision());
        assertEquals("invalid_model_output", result.reasonCode());
    }

    @Test
    void preservesPreferredModelFallbackWhenDedicatedModelIsNotConfigured() {
        IntentUnderstandingService service = service(true, 0.75, 4, 0L);
        when(aiModelService.chatWithModel(anyString(), anyString(), eq(99L)))
            .thenReturn(response("""
                {"route":"KNOWLEDGE","intent_code":"PRODUCT_USAGE",\
                "standalone_query":"点签怎么用", "entities":{},\
                "missing_slots":[],"context_dependent":false,"confidence":0.90}
                """));

        IntentUnderstandingService.Understanding result =
            service.understand("点签怎么用", List.of(), 99L);

        assertTrue(result.knowledge());
        verify(aiModelService).chatWithModel(anyString(), anyString(), eq(99L));
        verify(aiModelService, never()).chatWithExactModelJson(
            anyString(), anyString(), nullable(Long.class), anyMap());
    }

    @Test
    void skipsModelWhenDisabled() {
        IntentUnderstandingService service = service(false, 0.75, 4, 7L);

        IntentUnderstandingService.Understanding result =
            service.understand("这个怎么操作", List.of(), null);

        assertFalse(result.attempted());
        assertEquals("disabled", result.reasonCode());
        verify(aiModelService, never()).chatWithModel(
            anyString(), anyString(), nullable(Long.class));
        verify(aiModelService, never()).chatWithExactModelJson(
            anyString(), anyString(), nullable(Long.class), anyMap());
    }

    private IntentUnderstandingService service(boolean enabled, double confidence,
                                               int historyMessages, long modelId) {
        return new IntentUnderstandingService(
            aiModelService, new ObjectMapper(), enabled, confidence, historyMessages, modelId);
    }

    private void assertInvalid(IntentUnderstandingService.Understanding result) {
        assertTrue(result.attempted());
        assertFalse(result.actionable());
        assertEquals("invalid_model_output", result.reasonCode());
    }

    private ChatResponse response(String content) {
        return new ChatResponse(content, true, "intent-model", "test", 20, 10);
    }

    private BotMessage message(String role, String content) {
        BotMessage message = new BotMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private int occurrences(String value, String expected) {
        return value.split(java.util.regex.Pattern.quote(expected), -1).length - 1;
    }
}
