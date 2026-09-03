package com.feisheng.bot.core.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.dto.SafetyResult;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotAiReplyLogMapper;
import com.feisheng.bot.core.service.BusinessSafetyBoundaryService;
import com.feisheng.bot.core.service.ContextDecision;
import com.feisheng.bot.core.service.ContextModelCallPolicy;
import com.feisheng.bot.core.service.ConversationServiceImpl;
import com.feisheng.bot.core.service.CustomerServicePromptProvider;
import com.feisheng.bot.core.service.CustomerContextRecallService;
import com.feisheng.bot.core.service.CustomerContextSnapshot;
import com.feisheng.bot.core.service.EmotionService;
import com.feisheng.bot.core.service.HandoffCoordinator;
import com.feisheng.bot.core.service.IntentService;
import com.feisheng.bot.core.service.IntentUnderstandingService;
import com.feisheng.bot.core.service.NlpIntentClassifier;
import com.feisheng.bot.core.service.ReplyAttachmentService;
import com.feisheng.bot.core.service.SensitiveDataService;
import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LayeredContextMultiTurnTest {
    private static final Map<String, Object> FILTERS = Map.of("sourceScope", "KNOWLEDGE");

    @Mock private ConversationServiceImpl conversationService;
    @Mock private com.feisheng.bot.core.service.impl.MessageServiceImpl messageService;
    @Mock private com.feisheng.bot.core.service.impl.AiModelServiceImpl aiModelService;
    @Mock private com.feisheng.bot.core.service.impl.SafetyServiceImpl safetyService;
    @Mock private BotAiReplyLogMapper aiReplyLogMapper;
    @Mock private com.feisheng.bot.core.service.impl.RagRetrievalService retrievalService;
    @Mock private com.feisheng.bot.core.service.impl.UnmatchedQuestionService unmatchedQuestionService;
    @Mock private com.feisheng.bot.core.service.impl.BusinessToolOrchestrator businessToolOrchestrator;
    @Mock private IntentService intentService;
    @Mock private IntentUnderstandingService intentUnderstandingService;
    @Mock private CustomerContextRecallService customerContextRecallService;
    @Mock private HandoffCoordinator handoffCoordinator;
    @Mock private ReplyAttachmentService replyAttachmentService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BotConversation conversation = new BotConversation();
    private final List<BotMessage> messages = new ArrayList<>();
    private final AtomicLong messageIds = new AtomicLong(100);
    private DialogServiceImpl dialogService;

    @BeforeEach
    void setUp() {
        conversation.setId(24L);
        conversation.setChannelType("web");
        conversation.setChannelUserId("acceptance-user");
        lenient().when(conversationService.getOrCreate(anyString(), anyString(), anyString())).thenReturn(conversation);
        lenient().when(conversationService.getById(24L)).thenReturn(conversation);
        lenient().when(messageService.getByConversation(24L)).thenAnswer(invocation -> List.copyOf(messages));
        doAnswer(invocation -> {
            BotMessage message = invocation.getArgument(0);
            if (message.getId() == null) message.setId(messageIds.incrementAndGet());
            messages.add(message);
            return null;
        }).when(messageService).save(any(BotMessage.class));
        lenient().when(conversationService.updateDialogState(any(BotConversation.class), anyString(), any(Long.class)))
            .thenAnswer(invocation -> {
                BotConversation value = invocation.getArgument(0);
                value.setDialogState(invocation.getArgument(1));
                value.setDialogStateVersion(((Long) invocation.getArgument(2)) + 1L);
                return true;
            });
        lenient().when(safetyService.checkUserInput(anyString())).thenReturn(SafetyResult.pass());
        lenient().when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());
        lenient().when(aiModelService.chatWithModel(anyString(), anyString(), nullable(Long.class)))
            .thenReturn(new ChatResponse("测试客服答复", true, "acceptance-model", "test", 12, 6));
        lenient().when(handoffCoordinator.handoff(any(), anyString(), anyString()))
            .thenReturn(new HandoffCoordinator.HandoffResult(true, 1L, true, "test", null));
        lenient().when(replyAttachmentService.fromCitations(any(), anyBoolean())).thenReturn(Collections.emptyList());
        lenient().when(replyAttachmentService.fromKnowledgeImageTitle(anyString())).thenReturn(Collections.emptyList());
        lenient().when(intentService.match(anyString())).thenReturn(Optional.empty());
        lenient().when(retrievalService.retrieve(anyString(), nullable(String.class), nullable(String.class),
                eq(FILTERS), anyList(), eq(true))).thenReturn(answer("知识库确认的客服答案"));
        lenient().when(retrievalService.retrieve(anyString(), eq(FILTERS), eq(true)))
            .thenReturn(answer("知识库确认的客服答案"));
        dialogService = new DialogServiceImpl(
            conversationService, messageService, aiModelService, safetyService,
            new BusinessSafetyBoundaryService(), new CustomerServicePromptProvider("v1", "rag-system-prompt"),
            aiReplyLogMapper, retrievalService, unmatchedQuestionService, businessToolOrchestrator,
            intentService, new NlpIntentClassifier(), new SensitiveDataService("18689633999"),
            handoffCoordinator, new EmotionService(), replyAttachmentService,
            new ContextualQueryResolver(objectMapper), intentUnderstandingService, objectMapper);
        ReflectionTestUtils.setField(dialogService, "contextSummaryEnabled", false);
        ReflectionTestUtils.setField(dialogService, "layeredContextEnabled", true);
        ReflectionTestUtils.setField(dialogService, "layeredContextMaxCandidates", 12);
        ReflectionTestUtils.setField(dialogService, "layeredContextFastModelId", 11L);
        ReflectionTestUtils.setField(dialogService, "layeredContextDeepModelId", 22L);
        ReflectionTestUtils.setField(dialogService, "noAnswerReply", "暂无可核实资料");
        ReflectionTestUtils.setField(dialogService, "unrelatedReply", "暂无可核实资料");
        ReflectionTestUtils.setField(dialogService, "outOfScopeReply", "暂无可核实资料");
        ReflectionTestUtils.setField(dialogService, "errorReply", "查询失败");
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
    }

    @Test
    void customerFollowUpUsesResolvedQueryAndKeepsOriginalRequirement() {
        when(intentUnderstandingService.decideContext(any(), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
            .thenReturn(modelDecision(new ContextDecision(
                ContextDecision.Relation.NEW_TOPIC, "PRODUCT_USAGE", List.of(), List.of(),
                ContextDecision.TaskAction.CREATE, "task:usage", List.of(), "点签的使用教程", 0.96, false)))
            .thenReturn(modelDecision(new ContextDecision(
            ContextDecision.Relation.FOLLOW_UP, "PRODUCT_USAGE", List.of("task:active"), List.of(),
                ContextDecision.TaskAction.CONTINUE, "task:usage", List.of("视频形式"),
                "点签的使用教程，有没有视频教程？", 0.94, false)));

        dialogService.send("web", "acceptance-user", "点签的使用教程", "咨询");
        Map<String, Object> followUp = dialogService.send("web", "acceptance-user", "视频教程有没有？", "咨询");

        assertEquals("点签的使用教程，有没有视频教程？", followUp.get("resolvedQuery"));
        assertEquals("FAST_MODEL", followUp.get("contextDecisionRoute"));
        assertEquals(11L, followUp.get("contextFastModelId"));
        assertEquals(null, followUp.get("contextDeepModelId"));
        assertEquals("ACCEPTED", followUp.get("contextFastOutcome"));
        assertEquals(List.of("视频形式"), followUp.get("originalRequirements"));
        assertTrue(String.valueOf(followUp.get("retrievalPrimaryQuery")).contains("当前问题"));
    }

    @Test
    void newTopicDoesNotReusePreviousTopic() {
        when(intentUnderstandingService.decideContext(any(), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
            .thenReturn(modelDecision(decision(ContextDecision.Relation.NEW_TOPIC, "PRODUCT_USAGE", "点签教程")))
            .thenReturn(modelDecision(decision(ContextDecision.Relation.NEW_TOPIC, "ACCOUNT_OPERATION", "如何重置密码")));

        dialogService.send("web", "acceptance-user", "点签教程", "咨询");
        Map<String, Object> result = dialogService.send("web", "acceptance-user", "如何重置密码", "咨询");

        assertEquals("如何重置密码", result.get("resolvedQuery"));
        assertNotEquals("点签教程", result.get("resolvedQuery"));
        assertEquals("FAST_MODEL", result.get("contextDecisionRoute"));
    }

    @Test
    void correctionReplacesPreviousRequirementWithoutDroppingTurnMetadata() {
        when(intentUnderstandingService.decideContext(any(), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
            .thenReturn(modelDecision(decision(ContextDecision.Relation.NEW_TOPIC, "PRODUCT_USAGE", "点签教程")))
            .thenReturn(modelDecision(new ContextDecision(
            ContextDecision.Relation.CORRECTION, "PRODUCT_USAGE", List.of("task:active"), List.of(),
                ContextDecision.TaskAction.CONTINUE, "task:usage", List.of("图文形式"),
                "点签的使用教程，改成图文教程", 0.93, false)));

        dialogService.send("web", "acceptance-user", "点签教程", "咨询");
        Map<String, Object> result = dialogService.send("web", "acceptance-user", "不是视频，要图文教程", "咨询");

        assertEquals("点签的使用教程，改成图文教程", result.get("resolvedQuery"));
        assertEquals(List.of("图文形式"), result.get("originalRequirements"));
        assertEquals("FAST_MODEL", result.get("contextDecisionRoute"));
    }

    @Test
    void modelFailureFallsBackConservativelyAndDoesNotInventContext() {
        when(intentUnderstandingService.decideContext(any(), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
            .thenReturn(IntentUnderstandingService.ContextModelResult.failed("model_unavailable", 5L));

        Map<String, Object> result = dialogService.send("web", "acceptance-user", "视频教程有没有？", "咨询");

        assertEquals("FALLBACK", result.get("contextDecisionRoute"));
        assertEquals("model_unavailable", result.get("contextDecisionFallbackReason"));
        assertEquals("视频教程有没有？", result.get("originalQuery"));
        assertEquals("视频教程有没有？", result.get("resolvedQuery"));
    }

    @Test
    void customersAreIsolatedByChannelIdentity() {
        BotConversation other = new BotConversation();
        other.setId(25L);
        other.setChannelType("web");
        other.setChannelUserId("other-customer");
        when(conversationService.getOrCreate("web", "other-customer", "咨询")).thenReturn(other);
        when(conversationService.getById(25L)).thenReturn(other);
        when(messageService.getByConversation(25L)).thenReturn(List.of());
        when(intentUnderstandingService.decideContext(any(), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
            .thenReturn(modelDecision(decision(ContextDecision.Relation.FOLLOW_UP, "PRODUCT_USAGE", "错误客户上下文")));

        Map<String, Object> result = dialogService.send("web", "other-customer", "视频教程有没有？", "咨询");

        assertEquals("视频教程有没有？", result.get("resolvedQuery"));
        assertTrue(String.valueOf(result.get("contextCandidateIds")).equals("[]"));
    }

    @Test
    void customerCanContinueUsingRelevantMemoryFromAnotherSession() {
        ReflectionTestUtils.setField(dialogService, "customerContextRecallService", customerContextRecallService);
        CustomerContextSnapshot snapshot = new CustomerContextSnapshot(
            null, null, "", Map.of("totalMs", 1L), List.of(
                new CustomerContextSnapshot.ContextRecord(
                    "memory:preferred-format", "memory_fact", "客户上次选择视频教程",
                    7L, 88L, "web", "acceptance-user", 0.93D, null,
                    new Date(), null, "cross_session_memory")));
        when(customerContextRecallService.recall(anyString(), anyString(), any(), anyString(), any(), anyList()))
            .thenReturn(snapshot);
        when(intentUnderstandingService.decideContext(any(), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
            .thenReturn(modelDecision(new ContextDecision(
                ContextDecision.Relation.FOLLOW_UP, "PRODUCT_USAGE",
                List.of("memory:preferred-format"), List.of("memory:preferred-format"),
                ContextDecision.TaskAction.CONTINUE, "task:usage", List.of("视频形式"),
                "点签的使用教程，有没有视频教程？", 0.95D, false)));

        Map<String, Object> result = dialogService.send(
            "web", "acceptance-user", "有没有视频教程？", "跨会话追问");

        assertTrue(String.valueOf(result.get("contextCandidateIds")).contains("memory:preferred-format"));
        assertEquals(List.of("memory:preferred-format"), result.get("selectedMemoryIds"));
        assertEquals("点签的使用教程，有没有视频教程？", result.get("resolvedQuery"));
    }

    @Test
    void unrelatedCrossSessionMemoryIsRejectedByTheModelDecision() {
        ReflectionTestUtils.setField(dialogService, "customerContextRecallService", customerContextRecallService);
        CustomerContextSnapshot snapshot = new CustomerContextSnapshot(
            null, null, "", Map.of(), List.of(
                new CustomerContextSnapshot.ContextRecord(
                    "memory:unrelated", "memory_fact", "客户喜欢红烧肉",
                    7L, 89L, "web", "acceptance-user", 0.90D, null,
                    new Date(), null, "cross_session_memory")));
        when(customerContextRecallService.recall(anyString(), anyString(), any(), anyString(), any(), anyList()))
            .thenReturn(snapshot);
        when(intentUnderstandingService.decideContext(any(), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
            .thenReturn(modelDecision(new ContextDecision(
                ContextDecision.Relation.NEW_TOPIC, "PRODUCT_USAGE", List.of(), List.of(),
                ContextDecision.TaskAction.CREATE, "task:usage", List.of(),
                "如何发起合同？", 0.96D, false)));

        Map<String, Object> result = dialogService.send(
            "web", "acceptance-user", "如何发起合同？", "新话题");

        assertTrue(String.valueOf(result.get("contextCandidateIds")).contains("memory:unrelated"));
        assertEquals(List.of(), result.get("selectedMemoryIds"));
        assertEquals("如何发起合同？", result.get("resolvedQuery"));
    }

    @Test
    void customerCanPauseOneTaskAndResumeTheEarlierTask() {
        when(intentUnderstandingService.decideContext(any(), eq(11L),
                eq(ContextModelCallPolicy.Tier.FAST), anyLong()))
            .thenReturn(modelDecision(new ContextDecision(
                ContextDecision.Relation.NEW_TOPIC, "PRODUCT_USAGE", List.of(), List.of(),
                ContextDecision.TaskAction.CREATE, "task:usage", List.of(),
                "点签的使用教程", 0.96D, false)))
            .thenReturn(modelDecision(new ContextDecision(
                ContextDecision.Relation.NEW_TOPIC, "PRODUCT_FEATURES", List.of(), List.of(),
                ContextDecision.TaskAction.CREATE, "task:features", List.of(),
                "点签支持哪些功能？", 0.96D, false)))
            .thenReturn(modelDecision(new ContextDecision(
                ContextDecision.Relation.RESUME_TASK, "PRODUCT_USAGE", List.of("task:usage"), List.of(),
                ContextDecision.TaskAction.RESUME, "task:usage", List.of(),
                "继续教程任务", 0.94D, false)));

        dialogService.send("web", "acceptance-user", "点签的使用教程", "任务1");
        Map<String, Object> price = dialogService.send(
            "web", "acceptance-user", "点签支持哪些功能？", "任务2");
        Map<String, Object> resumed = dialogService.send(
            "web", "acceptance-user", "继续教程任务", "恢复任务1");

        assertEquals("task:features", price.get("contextTaskId"), price.toString());
        assertEquals("FAST_MODEL", resumed.get("contextDecisionRoute"), resumed.toString());
        assertEquals("task:usage", resumed.get("contextTaskId"));
        assertEquals("继续教程任务", resumed.get("resolvedQuery"));
        assertTrue(String.valueOf(resumed.get("contextTaskCollection")).contains("PAUSED"));
    }

    private IntentUnderstandingService.ContextModelResult modelDecision(ContextDecision decision) {
        return IntentUnderstandingService.ContextModelResult.success(decision, 3L);
    }

    private ContextDecision decision(ContextDecision.Relation relation, String intent, String query) {
        return new ContextDecision(relation, intent, List.of(), List.of(),
            relation == ContextDecision.Relation.NEW_TOPIC
                ? ContextDecision.TaskAction.CREATE : ContextDecision.TaskAction.CONTINUE,
            "task:acceptance", List.of(), query, 0.92, false);
    }

    private com.feisheng.bot.core.service.impl.RagRetrievalService.RetrievalResult answer(String text) {
        return new com.feisheng.bot.core.service.impl.RagRetrievalService.RetrievalResult(
            true, true, text, text, 0.95, "direct", false, List.of(), List.of());
    }
}
