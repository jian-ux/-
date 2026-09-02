package com.feisheng.bot.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.entity.BotConversation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class ConversationTaskManagerTest {

    private final ConversationTaskManager manager = new ConversationTaskManager();

    @Test
    void createsNewTopicAndPausesPreviouslyActiveTask() {
        ConversationTaskManager.TaskSnapshot first = manager.apply(24L,
                decision(ContextDecision.Relation.NEW_TOPIC, ContextDecision.TaskAction.CREATE,
                        "task:usage", "PRODUCT_USAGE", "点签的使用教程"),
                ConversationStateService.Snapshot.idle(0L));

        ConversationTaskManager.TaskSnapshot second = manager.apply(24L,
                decision(ContextDecision.Relation.NEW_TOPIC, ContextDecision.TaskAction.CREATE,
                        "task:price", "PRODUCT_VERSION_FEATURES", "点签企业版价格"),
                first.legacyState());

        assertEquals("task:price", second.selectedTaskId());
        assertEquals(ConversationTaskManager.Status.ACTIVE, second.activeTask().status());
        assertEquals(ConversationTaskManager.Status.PAUSED, second.tasks().get("task:usage").status());
        assertEquals(ConversationTaskManager.Status.ACTIVE, second.tasks().get("task:price").status());
    }

    @Test
    void continuesCurrentTaskAndResumesSelectedPausedTask() {
        ConversationTaskManager.TaskSnapshot usage = manager.apply(24L,
                decision(ContextDecision.Relation.NEW_TOPIC, ContextDecision.TaskAction.CREATE,
                        "task:usage", "PRODUCT_USAGE", "点签的使用教程"),
                ConversationStateService.Snapshot.idle(0L));
        ConversationTaskManager.TaskSnapshot price = manager.apply(24L,
                decision(ContextDecision.Relation.NEW_TOPIC, ContextDecision.TaskAction.CREATE,
                        "task:price", "PRODUCT_VERSION_FEATURES", "点签企业版价格"),
                usage.legacyState());

        ConversationTaskManager.TaskSnapshot continued = manager.apply(24L,
                decision(ContextDecision.Relation.FOLLOW_UP, ContextDecision.TaskAction.CONTINUE,
                        "task:price", "PRODUCT_VERSION_FEATURES", "点签企业版一年多少钱"),
                price.legacyState());
        ConversationTaskManager.TaskSnapshot resumed = manager.apply(24L,
                decision(ContextDecision.Relation.RESUME_TASK, ContextDecision.TaskAction.RESUME,
                        "task:usage", "PRODUCT_USAGE", "继续点签使用教程"),
                continued.legacyState());

        assertEquals("task:price", continued.selectedTaskId());
        assertEquals("task:usage", resumed.selectedTaskId());
        assertEquals(ConversationTaskManager.Status.ACTIVE, resumed.tasks().get("task:usage").status());
        assertEquals(ConversationTaskManager.Status.PAUSED, resumed.tasks().get("task:price").status());
    }

    @Test
    void preservesWaitingForUserAndClosesResolvedTask() {
        ConversationTaskManager.TaskSnapshot created = manager.apply(24L,
                decision(ContextDecision.Relation.NEW_TOPIC, ContextDecision.TaskAction.CREATE,
                        "task:login", "ACCOUNT_OPERATION", "企业账号登录失败"),
                ConversationStateService.Snapshot.idle(0L));
        ConversationTaskManager.TaskSnapshot waiting = manager.apply(24L,
                decision(ContextDecision.Relation.SLOT_FILL, ContextDecision.TaskAction.WAIT_FOR_USER,
                        "task:login", "ACCOUNT_OPERATION", "请补充登录报错信息"),
                created.legacyState());
        ConversationTaskManager.TaskSnapshot resolved = manager.apply(24L,
                decision(ContextDecision.Relation.FOLLOW_UP, ContextDecision.TaskAction.COMPLETE,
                        "task:login", "ACCOUNT_OPERATION", "企业账号登录问题已解决"),
                waiting.legacyState());

        assertEquals(ConversationTaskManager.Status.WAITING_FOR_USER,
                waiting.tasks().get("task:login").status());
        assertEquals(ConversationStateService.Status.WAITING_FOR_SLOT, waiting.legacyState().status());
        assertEquals(ConversationTaskManager.Status.RESOLVED,
                resolved.tasks().get("task:login").status());
        assertNull(resolved.activeTask());
        assertEquals(ConversationStateService.Status.IDLE, resolved.legacyState().status());
    }

    @Test
    void convertsLegacySingleStateIntoTaskCollection() {
        ConversationStateService.Snapshot legacy = new ConversationStateService.Snapshot(
                ConversationStateService.Status.ACTIVE, "SYSTEM_INTEGRATION",
                Map.of("business_system", "CRM"), List.of(),
                "点签是否支持接入CRM？", null, 0, 3, 7L);

        ConversationTaskManager.TaskSnapshot result = manager.apply(24L,
                decision(ContextDecision.Relation.FOLLOW_UP, ContextDecision.TaskAction.CONTINUE,
                        "", "SYSTEM_INTEGRATION", "点签是否支持接入ERP？"), legacy);

        assertEquals("legacy:24", result.selectedTaskId());
        assertEquals(Map.of("business_system", "CRM"), result.activeTask().slots());
        assertEquals(7L, result.legacyState().version());
        assertEquals("legacy:24", result.legacyState().selectedTaskId());
        assertEquals(1, result.legacyState().tasks().size());
    }

    @Test
    void persistsAndReloadsBoundedTaskCollectionAlongsideLegacyFields() throws Exception {
        ConversationTaskManager.TaskSnapshot created = manager.apply(24L,
                decision(ContextDecision.Relation.NEW_TOPIC, ContextDecision.TaskAction.CREATE,
                        "task:usage", "PRODUCT_USAGE", "点签的使用教程"),
                ConversationStateService.Snapshot.idle(3L));
        ObjectMapper objectMapper = new ObjectMapper();
        BotConversation conversation = new BotConversation();
        conversation.setId(24L);
        conversation.setDialogStateVersion(3L);
        conversation.setDialogState(objectMapper.writeValueAsString(created.legacyState().toMap()));

        ConversationStateService.Snapshot loaded = new ConversationStateService(
                mock(ConversationServiceImpl.class), objectMapper).load(conversation, List.of());

        assertEquals(ConversationStateService.Status.ACTIVE, loaded.status());
        assertEquals("task:usage", loaded.selectedTaskId());
        assertEquals(ConversationTaskManager.Status.ACTIVE,
                loaded.tasks().get("task:usage").status());
        assertEquals("点签的使用教程", loaded.standaloneQuery());
    }

    private ContextDecision decision(ContextDecision.Relation relation, ContextDecision.TaskAction action,
                                     String taskId, String intent, String query) {
        return new ContextDecision(relation, intent, List.of(), List.of(), action, taskId,
                List.of(), query, 0.90D, false);
    }
}
