package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.entity.BotToolExecutionLog;
import com.feisheng.bot.core.mapper.BotToolExecutionLogMapper;
import com.feisheng.bot.core.service.BusinessDataProvider;
import com.feisheng.bot.core.service.tool.CustomerServiceTool;
import com.feisheng.bot.core.service.tool.OrderReferenceResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessToolOrchestratorTest {
    @Mock private CustomerServiceTool tool;
    @Mock private BotToolExecutionLogMapper executionLogMapper;

    private BusinessToolOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        when(tool.name()).thenReturn("order.query");
        orchestrator = new BusinessToolOrchestrator(List.of(tool),
            new OrderReferenceResolver(), executionLogMapper, new ObjectMapper());
    }

    @Test
    void asksForOrderNumberWhenIntentHasNoReference() {
        when(tool.matches(anyString(), anyList())).thenReturn(true);

        BusinessToolOrchestrator.ToolRoutingResult result = orchestrator.route(
            1L, "web", "user-1", "帮我查订单状态", List.of());

        assertTrue(result.handled());
        assertEquals("needs_input", result.status());
        assertTrue(result.reply().contains("请提供要查询的订单号"));
        assertFalse(result.needsTransfer());
        verify(executionLogMapper).insert(any(BotToolExecutionLog.class));
    }

    @Test
    void executesToolAndStoresOnlyMaskedOrderReference() {
        when(tool.matches(anyString(), anyList())).thenReturn(true);
        when(tool.execute(any())).thenReturn(new CustomerServiceTool.ToolExecutionResult(
            BusinessDataProvider.QueryStatus.FOUND, "订单已发货。", "local"));

        BusinessToolOrchestrator.ToolRoutingResult result = orchestrator.route(
            1L, "web", "user-1", "查订单 FS202607170001", List.of());

        assertEquals("answered", result.status());
        assertEquals("订单已发货。", result.reply());
        ArgumentCaptor<BotToolExecutionLog> captor =
            ArgumentCaptor.forClass(BotToolExecutionLog.class);
        verify(executionLogMapper).insert(captor.capture());
        assertTrue(captor.getValue().getInputJson().contains("0001"));
        assertFalse(captor.getValue().getInputJson().contains("FS202607170001"));
    }
}
