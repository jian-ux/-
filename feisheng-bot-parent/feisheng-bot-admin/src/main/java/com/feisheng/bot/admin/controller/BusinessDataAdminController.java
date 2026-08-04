package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.core.entity.BotBusinessLogistics;
import com.feisheng.bot.core.entity.BotBusinessOrder;
import com.feisheng.bot.core.entity.BotToolExecutionLog;
import com.feisheng.bot.core.mapper.BotBusinessLogisticsMapper;
import com.feisheng.bot.core.mapper.BotBusinessOrderMapper;
import com.feisheng.bot.core.mapper.BotToolExecutionLogMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/business")
public class BusinessDataAdminController {
    private final BotBusinessOrderMapper orderMapper;
    private final BotBusinessLogisticsMapper logisticsMapper;
    private final BotToolExecutionLogMapper executionLogMapper;

    public BusinessDataAdminController(BotBusinessOrderMapper orderMapper,
                                       BotBusinessLogisticsMapper logisticsMapper,
                                       BotToolExecutionLogMapper executionLogMapper) {
        this.orderMapper = orderMapper;
        this.logisticsMapper = logisticsMapper;
        this.executionLogMapper = executionLogMapper;
    }

    @GetMapping("/order/list")
    public R<Page<BotBusinessOrder>> orders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String orderNo) {
        LambdaQueryWrapper<BotBusinessOrder> query = new LambdaQueryWrapper<>();
        if (hasText(orderNo)) query.like(BotBusinessOrder::getOrderNo, orderNo.trim());
        query.orderByDesc(BotBusinessOrder::getOrderTime)
            .orderByDesc(BotBusinessOrder::getId);
        return R.ok(orderMapper.selectPage(new Page<>(page, size), query));
    }

    @PostMapping("/order/save")
    public R<Void> saveOrder(@RequestBody BotBusinessOrder order) {
        if (order == null || !hasText(order.getOrderNo())
                || !hasText(order.getChannelType()) || !hasText(order.getChannelUserId())) {
            return R.fail(400, "订单编号、渠道类型和客户用户名不能为空");
        }
        order.setOrderNo(order.getOrderNo().trim().toUpperCase());
        if (order.getId() == null) {
            BotBusinessOrder existing = orderMapper.selectOne(
                new LambdaQueryWrapper<BotBusinessOrder>()
                    .eq(BotBusinessOrder::getOrderNo, order.getOrderNo())
                    .last("LIMIT 1"));
            if (existing != null) order.setId(existing.getId());
        }
        if (order.getId() == null) orderMapper.insert(order);
        else orderMapper.updateById(order);
        return R.ok();
    }

    @GetMapping("/logistics/list")
    public R<Page<BotBusinessLogistics>> logistics(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String orderNo) {
        LambdaQueryWrapper<BotBusinessLogistics> query = new LambdaQueryWrapper<>();
        if (hasText(orderNo)) query.like(BotBusinessLogistics::getOrderNo, orderNo.trim());
        query.orderByDesc(BotBusinessLogistics::getLatestEventTime)
            .orderByDesc(BotBusinessLogistics::getId);
        return R.ok(logisticsMapper.selectPage(new Page<>(page, size), query));
    }

    @PostMapping("/logistics/save")
    public R<Void> saveLogistics(@RequestBody BotBusinessLogistics logistics) {
        if (logistics == null || !hasText(logistics.getOrderNo())) {
            return R.fail(400, "订单编号不能为空");
        }
        logistics.setOrderNo(logistics.getOrderNo().trim().toUpperCase());
        if (logistics.getId() == null) {
            BotBusinessLogistics existing = logisticsMapper.selectOne(
                new LambdaQueryWrapper<BotBusinessLogistics>()
                    .eq(BotBusinessLogistics::getOrderNo, logistics.getOrderNo())
                    .last("LIMIT 1"));
            if (existing != null) logistics.setId(existing.getId());
        }
        if (logistics.getId() == null) logisticsMapper.insert(logistics);
        else logisticsMapper.updateById(logistics);
        return R.ok();
    }

    @GetMapping("/tool-log/list")
    public R<Page<BotToolExecutionLog>> toolLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String toolName) {
        LambdaQueryWrapper<BotToolExecutionLog> query = new LambdaQueryWrapper<>();
        if (hasText(toolName)) query.eq(BotToolExecutionLog::getToolName, toolName.trim());
        query.orderByDesc(BotToolExecutionLog::getCreateTime)
            .orderByDesc(BotToolExecutionLog::getId);
        return R.ok(executionLogMapper.selectPage(new Page<>(page, size), query));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
