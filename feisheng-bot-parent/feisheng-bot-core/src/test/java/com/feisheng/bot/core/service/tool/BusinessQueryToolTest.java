package com.feisheng.bot.core.service.tool;

import com.feisheng.bot.core.service.BusinessDataProvider;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessQueryToolTest {
    private static final BusinessDataProvider.QueryIdentity IDENTITY =
        new BusinessDataProvider.QueryIdentity("web", "user-1");

    @Test
    void formatsOrderFromStructuredBusinessData() {
        BusinessDataProvider provider = mock(BusinessDataProvider.class);
        when(provider.providerCode()).thenReturn("test");
        when(provider.findOrder(IDENTITY, "FS202607170001", "req-1")).thenReturn(
            BusinessDataProvider.QueryResult.found(new BusinessDataProvider.OrderView(
                "FS202607170001", "已发货", "已支付", "专业版套餐",
                19900L, "CNY", null)));
        OrderQueryTool tool = new OrderQueryTool(provider, new OrderReferenceResolver());

        CustomerServiceTool.ToolExecutionResult result = tool.execute(
            new CustomerServiceTool.ToolExecutionContext(
                1L, IDENTITY, "查订单", "FS202607170001", "req-1"));

        assertEquals(BusinessDataProvider.QueryStatus.FOUND, result.status());
        assertEquals("订单 FS202607170001 当前状态：已发货；支付状态：已支付；商品：专业版套餐；金额：¥199.00。",
            result.reply());
    }

    @Test
    void refusesToRevealOrderWhenOwnershipCannotBeVerified() {
        BusinessDataProvider provider = mock(BusinessDataProvider.class);
        when(provider.providerCode()).thenReturn("test");
        when(provider.findOrder(IDENTITY, "FS202607170001", "req-2"))
            .thenReturn(BusinessDataProvider.QueryResult.forbidden());
        OrderQueryTool tool = new OrderQueryTool(provider, new OrderReferenceResolver());

        CustomerServiceTool.ToolExecutionResult result = tool.execute(
            new CustomerServiceTool.ToolExecutionContext(
                1L, IDENTITY, "查订单", "FS202607170001", "req-2"));

        assertEquals(BusinessDataProvider.QueryStatus.FORBIDDEN, result.status());
        assertTrue(result.reply().contains("无法验证该订单的归属"));
    }

    @Test
    void formatsLogisticsFromStructuredBusinessData() {
        BusinessDataProvider provider = mock(BusinessDataProvider.class);
        when(provider.providerCode()).thenReturn("test");
        when(provider.findLogistics(IDENTITY, "FS202607170001", "req-3")).thenReturn(
            BusinessDataProvider.QueryResult.found(new BusinessDataProvider.LogisticsView(
                "FS202607170001", "顺丰速运", "SF001", "运输中",
                "已到达转运中心", new Date(0), null)));
        LogisticsQueryTool tool = new LogisticsQueryTool(provider, new OrderReferenceResolver());

        CustomerServiceTool.ToolExecutionResult result = tool.execute(
            new CustomerServiceTool.ToolExecutionContext(
                1L, IDENTITY, "查物流", "FS202607170001", "req-3"));

        assertEquals(BusinessDataProvider.QueryStatus.FOUND, result.status());
        assertTrue(result.reply().contains("顺丰速运"));
        assertTrue(result.reply().contains("已到达转运中心"));
    }

    @Test
    void recognizesColloquialReadOnlyIntents() {
        BusinessDataProvider provider = mock(BusinessDataProvider.class);
        OrderReferenceResolver resolver = new OrderReferenceResolver();

        assertTrue(new OrderQueryTool(provider, resolver)
            .matches("我这个单子咋样了？", java.util.List.of()));
        assertTrue(new LogisticsQueryTool(provider, resolver)
            .matches("啥时候发货呀？", java.util.List.of()));
        assertFalse(new OrderQueryTool(provider, resolver)
            .matches("电子合同怎么签署？", java.util.List.of()));
    }
}
