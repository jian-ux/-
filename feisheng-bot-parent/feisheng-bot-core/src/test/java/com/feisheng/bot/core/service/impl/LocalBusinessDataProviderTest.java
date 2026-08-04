package com.feisheng.bot.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.feisheng.bot.core.entity.BotBusinessLogistics;
import com.feisheng.bot.core.entity.BotBusinessOrder;
import com.feisheng.bot.core.mapper.BotBusinessLogisticsMapper;
import com.feisheng.bot.core.mapper.BotBusinessOrderMapper;
import com.feisheng.bot.core.service.BusinessDataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalBusinessDataProviderTest {
    @Mock private BotBusinessOrderMapper orderMapper;
    @Mock private BotBusinessLogisticsMapper logisticsMapper;

    private LocalBusinessDataProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalBusinessDataProvider(orderMapper, logisticsMapper);
    }

    @Test
    void returnsOrderOnlyToItsChannelOwner() {
        BotBusinessOrder order = order("playground", "admin-preview");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        BusinessDataProvider.QueryResult<BusinessDataProvider.OrderView> own = provider.findOrder(
            new BusinessDataProvider.QueryIdentity("playground", "admin-preview"),
            order.getOrderNo(), "req-1");
        BusinessDataProvider.QueryResult<BusinessDataProvider.OrderView> other = provider.findOrder(
            new BusinessDataProvider.QueryIdentity("web", "other-user"),
            order.getOrderNo(), "req-2");

        assertEquals(BusinessDataProvider.QueryStatus.FOUND, own.status());
        assertEquals(BusinessDataProvider.QueryStatus.FORBIDDEN, other.status());
    }

    @Test
    void doesNotReadLogisticsAfterOwnershipFailure() {
        when(orderMapper.selectOne(any(Wrapper.class)))
            .thenReturn(order("playground", "admin-preview"));

        BusinessDataProvider.QueryResult<BusinessDataProvider.LogisticsView> result =
            provider.findLogistics(
                new BusinessDataProvider.QueryIdentity("web", "other-user"),
                "FS202607170001", "req-3");

        assertEquals(BusinessDataProvider.QueryStatus.FORBIDDEN, result.status());
        verify(logisticsMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void returnsOwnedLogistics() {
        when(orderMapper.selectOne(any(Wrapper.class)))
            .thenReturn(order("playground", "admin-preview"));
        BotBusinessLogistics logistics = new BotBusinessLogistics();
        logistics.setOrderNo("FS202607170001");
        logistics.setCarrier("顺丰速运");
        logistics.setStatus("运输中");
        when(logisticsMapper.selectOne(any(Wrapper.class))).thenReturn(logistics);

        BusinessDataProvider.QueryResult<BusinessDataProvider.LogisticsView> result =
            provider.findLogistics(
                new BusinessDataProvider.QueryIdentity("playground", "admin-preview"),
                "FS202607170001", "req-4");

        assertEquals(BusinessDataProvider.QueryStatus.FOUND, result.status());
        assertEquals("顺丰速运", result.data().carrier());
    }

    private BotBusinessOrder order(String channelType, String channelUserId) {
        BotBusinessOrder order = new BotBusinessOrder();
        order.setOrderNo("FS202607170001");
        order.setChannelType(channelType);
        order.setChannelUserId(channelUserId);
        order.setStatus("已发货");
        return order;
    }
}
