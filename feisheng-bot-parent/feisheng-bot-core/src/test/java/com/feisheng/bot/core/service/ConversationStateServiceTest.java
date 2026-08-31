package com.feisheng.bot.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.entity.BotConversation;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationStateServiceTest {
    @Mock private ConversationServiceImpl conversationService;

    private ObjectMapper objectMapper;
    private ConversationStateService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ConversationStateService(conversationService, objectMapper);
    }

    @Test
    void loadsPersistedStateAndExposesOnlyModelContext() {
        BotConversation conversation = conversation(10L, 4L, """
            {"schemaVersion":1,"status":"ACTIVE",\
             "activeIntent":"SYSTEM_INTEGRATION",\
             "entities":{"business_system":"CRM"},"missingSlots":[],\
             "standaloneQuery":"点签是否支持集成到CRM系统？",\
             "pending":null,"clarificationAttempts":0,"remainingTurns":3}
            """);

        ConversationStateService.Snapshot snapshot = service.load(conversation, List.of());
        Map<String, Object> context = service.modelContext(snapshot);

        assertEquals(ConversationStateService.Status.ACTIVE, snapshot.status());
        assertEquals(4L, snapshot.version());
        assertEquals(Map.of("business_system", "CRM"), snapshot.entities());
        assertEquals("SYSTEM_INTEGRATION", context.get("active_intent"));
        assertFalse(context.containsKey("schemaVersion"));
        assertFalse(context.containsKey("version"));
    }

    @Test
    void replacesBusinessSystemInAnIntegrationFollowUp() {
        ConversationStateService.Snapshot state = new ConversationStateService.Snapshot(
            ConversationStateService.Status.ACTIVE, "SYSTEM_INTEGRATION",
            Map.of("business_system", "CRM"), List.of(),
            "点签电子签章是否支持通过API集成到CRM系统？",
            null, 0, 3, 2L);

        ConversationStateService.MergeResult result = service.merge(
            state, "那我们的ERP系统呢？",
            IntentUnderstandingService.Understanding.notAttempted("test"),
            new NlpIntentClassifier().classify("那我们的ERP系统呢？"));

        assertFalse(result.retainPending());
        assertTrue(result.understanding().knowledge());
        assertEquals("SYSTEM_INTEGRATION", result.understanding().intentCode());
        assertEquals("ERP", result.understanding().entities().get("business_system"));
        assertTrue(result.understanding().standaloneQuery().contains("ERP"));
        assertEquals("java_state_entity_replacement", result.reasonCode());
    }

    @Test
    void keepsInvalidSlotReplyButClearsPendingForCompleteNewQuestion() {
        ConversationStateService.PendingState pending =
            new ConversationStateService.PendingState(
                "UNKNOWN", "context", "{context}", "请补充场景", 1, 2,
                "unresolved_reference", "这个怎么操作");
        ConversationStateService.Snapshot state = new ConversationStateService.Snapshot(
            ConversationStateService.Status.WAITING_FOR_SLOT, "UNKNOWN", Map.of(),
            List.of("context"), "这个怎么操作", pending, 1, 1, 1L);

        ConversationStateService.MergeResult invalid = service.merge(
            state, "不知道",
            IntentUnderstandingService.Understanding.notAttempted("test"),
            new NlpIntentClassifier().classify("不知道"));
        ConversationStateService.MergeResult complete = service.merge(
            state, "点签可以嵌入ERP系统吗？",
            IntentUnderstandingService.Understanding.notAttempted("test"),
            new NlpIntentClassifier().classify("点签可以嵌入ERP系统吗？"));

        assertTrue(invalid.retainPending());
        assertFalse(complete.retainPending());
        assertEquals("SYSTEM_INTEGRATION", complete.understanding().intentCode());

        Map<String, Object> retainedContext = service.turnContext(state, invalid, "不知道");
        Map<String, Object> newQuestionContext = service.turnContext(
            state, complete, "点签可以嵌入ERP系统吗？");
        assertEquals("WAITING_FOR_SLOT", retainedContext.get("status"));
        assertTrue(retainedContext.containsKey("pending_clarification"));
        assertEquals("SYSTEM_INTEGRATION", newQuestionContext.get("active_intent"));
        assertFalse(newQuestionContext.containsKey("pending_clarification"));
        assertFalse(newQuestionContext.toString().contains("这个怎么操作"));
    }

    @Test
    void serializesClarificationStateThroughOptimisticUpdate() throws Exception {
        BotConversation conversation = conversation(10L, 0L, null);
        when(conversationService.getById(10L)).thenReturn(conversation);
        when(conversationService.updateDialogState(eq(conversation),
                org.mockito.ArgumentMatchers.anyString(), eq(0L)))
            .thenReturn(true);

        service.synchronizeResponse(Map.of(
            "conversationId", 10L,
            "answerDecision", "CLARIFY",
            "pendingClarification", Map.of(
                "intentCode", "UNKNOWN", "missingSlot", "context",
                "queryTemplate", "{context}", "question", "请补充具体场景",
                "attempt", 1, "maxAttempts", 2,
                "reasonCode", "unresolved_reference")), "这个怎么操作");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(conversationService).updateDialogState(
            eq(conversation), json.capture(), eq(0L));
        JsonNode state = objectMapper.readTree(json.getValue());
        assertEquals("WAITING_FOR_SLOT", state.path("status").asText());
        assertEquals("context", state.path("pending").path("missingSlot").asText());
        assertEquals("这个怎么操作", state.path("pending").path("sourceQuestion").asText());
    }

    @Test
    void keepsActiveTopicWhenSynchronizingHistoryRecall() throws Exception {
        BotConversation conversation = conversation(10L, 3L, """
            {"schemaVersion":1,"status":"ACTIVE",\
             "activeIntent":"SYSTEM_INTEGRATION","entities":{"business_system":"CRM"},\
             "missingSlots":[],"standaloneQuery":"点签可以集成CRM吗？",\
             "pending":null,"clarificationAttempts":0,"remainingTurns":3}
        """);
        when(conversationService.getById(10L)).thenReturn(conversation);

        service.synchronizeResponse(Map.of(
            "conversationId", 10L,
            "answerDecision", "ANSWER",
            "intentUnderstanding", Map.of(
                "intentCode", "HISTORY_RECALL", "standaloneQuery", "企业认证")),
            "我之前咨询过哪个认证？");

        verify(conversationService, org.mockito.Mockito.never()).updateDialogState(
            eq(conversation), org.mockito.ArgumentMatchers.anyString(), eq(3L));
        JsonNode state = objectMapper.readTree(conversation.getDialogState());
        assertEquals("SYSTEM_INTEGRATION", state.path("activeIntent").asText());
        assertEquals("CRM", state.path("entities").path("business_system").asText());
    }

    private BotConversation conversation(Long id, Long version, String state) {
        BotConversation conversation = new BotConversation();
        conversation.setId(id);
        conversation.setDialogStateVersion(version);
        conversation.setDialogState(state);
        return conversation;
    }
}
