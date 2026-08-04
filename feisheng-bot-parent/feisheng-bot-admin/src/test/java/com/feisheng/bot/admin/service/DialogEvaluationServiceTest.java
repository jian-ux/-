package com.feisheng.bot.admin.service;

import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.mapper.BotConversationMapper;
import com.feisheng.bot.core.mapper.BotMessageMapper;
import com.feisheng.bot.core.service.SensitiveDataService;
import com.feisheng.bot.core.service.impl.DialogServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DialogEvaluationServiceTest {
    @Mock private DialogServiceImpl dialogService;
    @Mock private BotConversationMapper conversationMapper;
    @Mock private BotMessageMapper messageMapper;
    @Mock private TransactionTemplate transactionTemplate;

    private DialogEvaluationService service;
    private final List<Boolean> rollbackFlags = new ArrayList<>();

    @BeforeEach
    void setUp() {
        SensitiveDataService sensitiveDataService = new SensitiveDataService("18689633999");
        service = new DialogEvaluationService(dialogService, conversationMapper, messageMapper,
            sensitiveDataService, new ObjectMapper(), transactionTemplate);
        doAnswer(invocation -> {
            BotConversation conversation = invocation.getArgument(0);
            conversation.setId(100L + rollbackFlags.size());
            return 1;
        }).when(conversationMapper).insert(any(BotConversation.class));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            TransactionStatus status = new SimpleTransactionStatus();
            Object result = callback.doInTransaction(status);
            rollbackFlags.add(status.isRollbackOnly());
            return result;
        });
    }

    @Test
    void evaluatesFinalRepliesAndRollsBackEveryCase() {
        Map<String, Object> citation = Map.of("sourceType", "document", "sourceId", 7L);
        when(dialogService.send(eq("evaluation"), anyString(),
                eq("手机号13800138000，怎么重置密码"), anyString(), isNull(), isNull()))
            .thenReturn(response("请点击忘记密码完成重置。", "answered", "rag_ai",
                0.86, List.of(citation), false, false));
        when(dialogService.send(eq("evaluation"), anyString(),
                eq("火星办公室几点开门"), anyString(), isNull(), isNull()))
            .thenReturn(response("现有知识不足，已转接人工客服。", "no_answer", "no_answer",
                0.2, List.of(), true, false));

        DialogEvaluationService.DialogEvaluationReport report = service.evaluate(
            new DialogEvaluationService.DialogEvaluationRequest("e2e-smoke", List.of(
                new DialogEvaluationService.DialogEvaluationCase(
                    "known", "手机号13800138000，怎么重置密码", true, null, "ANSWER",
                    "document", 7L,
                    List.of(new DialogEvaluationService.EvaluationTurn("user", "我忘记密码了")),
                    List.of("重置"), List.of("不知道"), false, null),
                new DialogEvaluationService.DialogEvaluationCase(
                    "unknown", "火星办公室几点开门", false, null, "NO_KNOWLEDGE",
                    null, null, List.of(), List.of(), List.of(), true, null))));

        assertEquals(1.0, report.decisionAccuracy());
        assertEquals(1.0, report.groundingAccuracy());
        assertEquals(1.0, report.requiredPhraseHitRate());
        assertEquals(0, report.forbiddenPhraseViolations());
        assertEquals(1.0, report.handoffAccuracy());
        assertEquals(0, report.piiLeakCount());
        assertEquals(List.of(true, true), rollbackFlags);
        assertTrue(report.databaseRolledBack());
        assertEquals("手机号[手机号已脱敏]，怎么重置密码",
            report.cases().get(0).question());
    }

    private Map<String, Object> response(String reply, String status, String source,
                                         double confidence,
                                         List<Map<String, Object>> citations,
                                         boolean needsTransfer, boolean redactionApplied) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("answerStatus", status);
        response.put("answerDecision", "answered".equals(status) ? "ANSWER" : "NO_KNOWLEDGE");
        response.put("source", source);
        response.put("confidence", confidence);
        response.put("citations", citations);
        response.put("needsTransfer", needsTransfer);
        response.put("redactionApplied", redactionApplied);
        response.put("redactedTypes", redactionApplied ? List.of("PHONE") : List.of());
        if (needsTransfer) {
            response.put("handoff", Map.of(
                "ticketId", 99L, "success", true,
                "summary", "已脱敏的会话摘要"));
        }
        return response;
    }
}
