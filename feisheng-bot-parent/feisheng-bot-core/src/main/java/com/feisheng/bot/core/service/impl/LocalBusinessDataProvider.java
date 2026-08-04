package com.feisheng.bot.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.core.entity.BotBusinessLogistics;
import com.feisheng.bot.core.entity.BotBusinessOrder;
import com.feisheng.bot.core.mapper.BotBusinessLogisticsMapper;
import com.feisheng.bot.core.mapper.BotBusinessOrderMapper;
import com.feisheng.bot.core.service.BusinessDataProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@ConditionalOnProperty(prefix = "business.api", name = "enabled",
    havingValue = "false", matchIfMissing = true)
public class LocalBusinessDataProvider implements BusinessDataProvider {
    private final BotBusinessOrderMapper orderMapper;
    private final BotBusinessLogisticsMapper logisticsMapper;

    public LocalBusinessDataProvider(BotBusinessOrderMapper orderMapper,
                                     BotBusinessLogisticsMapper logisticsMapper) {
        this.orderMapper = orderMapper;
        this.logisticsMapper = logisticsMapper;
    }

    @Override
    public String providerCode() {
        return "local";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public QueryResult<OrderView> findOrder(QueryIdentity identity, String orderNo,
                                            String requestId) {
        try {
            BotBusinessOrder order = selectOrder(orderNo);
            if (order == null) return QueryResult.notFound();
            if (!ownedBy(order, identity)) return QueryResult.forbidden();
            return QueryResult.found(new OrderView(
                order.getOrderNo(), order.getStatus(), order.getPaymentStatus(),
                order.getItemSummary(), order.getAmountCents(), order.getCurrency(),
                order.getOrderTime()));
        } catch (Exception e) {
            return QueryResult.error("订单数据查询失败");
        }
    }

    @Override
    public QueryResult<LogisticsView> findLogistics(QueryIdentity identity, String orderNo,
                                                    String requestId) {
        try {
            BotBusinessOrder order = selectOrder(orderNo);
            if (order == null) return QueryResult.notFound();
            if (!ownedBy(order, identity)) return QueryResult.forbidden();
            BotBusinessLogistics logistics = logisticsMapper.selectOne(
                new LambdaQueryWrapper<BotBusinessLogistics>()
                    .eq(BotBusinessLogistics::getOrderNo, orderNo)
                    .last("LIMIT 1"));
            if (logistics == null) return QueryResult.notFound();
            return QueryResult.found(new LogisticsView(
                logistics.getOrderNo(), logistics.getCarrier(), logistics.getTrackingNo(),
                logistics.getStatus(), logistics.getLatestEvent(),
                logistics.getLatestEventTime(), logistics.getEstimatedDeliveryTime()));
        } catch (Exception e) {
            return QueryResult.error("物流数据查询失败");
        }
    }

    private BotBusinessOrder selectOrder(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<BotBusinessOrder>()
            .eq(BotBusinessOrder::getOrderNo, orderNo)
            .last("LIMIT 1"));
    }

    private boolean ownedBy(BotBusinessOrder order, QueryIdentity identity) {
        return identity != null
            && Objects.equals(order.getChannelType(), identity.channelType())
            && Objects.equals(order.getChannelUserId(), identity.channelUserId());
    }
}
