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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntentUnderstandingServiceTest {
    @Mock private AiModelServiceImpl aiModelService;

    @Test
    void returnsStandaloneKnowledgeQueryFromRecentContext() {
        IntentUnderstandingService service = service(true, 0.75, 4);
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
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
                message("user", "这个怎么操作")), null);

        assertTrue(result.knowledge());
        assertEquals("ACCOUNT_OPERATION", result.intentCode());
        assertEquals("点签企业账号如何登录？", result.standaloneQuery());
        assertEquals(Map.of("user_type", "企业"), result.entities());
        assertTrue(result.contextDependent());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(prompt.capture(), anyString(), isNull());
        assertTrue(prompt.getValue().contains("企业账号登录失败"));
        assertEquals(1, occurrences(prompt.getValue(), "这个怎么操作"));
    }

    @Test
    void rejectsLowConfidenceResultWithoutLosingDiagnostics() {
        IntentUnderstandingService service = service(true, 0.75, 4);
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
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
        IntentUnderstandingService service = service(true, 0.75, 4);
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(response("""
                {"route":"CLARIFY","intent_code":"ACCOUNT_OPERATION",\
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
        IntentUnderstandingService service = service(true, 0.75, 4);
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(response("""
                {"route":"CLARIFY","intent_code":"ACCOUNT_OPERATION",\
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
        IntentUnderstandingService service = service(true, 0.75, 4);
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
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
    void skipsModelWhenDisabled() {
        IntentUnderstandingService service = service(false, 0.75, 4);

        IntentUnderstandingService.Understanding result =
            service.understand("这个怎么操作", List.of(), null);

        assertFalse(result.attempted());
        assertEquals("disabled", result.reasonCode());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), isNull());
    }

    private IntentUnderstandingService service(boolean enabled, double confidence,
                                               int historyMessages) {
        return new IntentUnderstandingService(
            aiModelService, new ObjectMapper(), enabled, confidence, historyMessages);
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
