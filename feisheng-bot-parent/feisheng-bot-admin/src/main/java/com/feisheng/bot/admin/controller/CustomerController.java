package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.entity.BotCustomer;
import com.feisheng.bot.admin.mapper.BotConversationMapper;
import com.feisheng.bot.admin.mapper.BotCustomerMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/customer")
public class CustomerController {
    private final BotCustomerMapper mapper;
    private final BotConversationMapper conversationMapper;

    public CustomerController(BotCustomerMapper mapper,
                              BotConversationMapper conversationMapper) {
        this.mapper = mapper;
        this.conversationMapper = conversationMapper;
    }

    @GetMapping("/list")
    public R<Page<BotCustomer>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<BotCustomer> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(BotCustomer::getName, keyword)
                .or().like(BotCustomer::getPhone, keyword)
                .or().like(BotCustomer::getNickname, keyword));
        }
        q.orderByDesc(BotCustomer::getLastContactTime);
        return R.ok(mapper.selectPage(new Page<>(page, size), q));
    }

    @GetMapping("/{id}")
    public R<BotCustomer> detail(@PathVariable Long id) {
        return R.ok(mapper.selectById(id));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody BotCustomer changes) {
        BotCustomer customer = mapper.selectById(id);
        if (customer == null) return R.fail(404, "客户不存在");
        customer.setName(trimToNull(changes.getName()));
        customer.setPhone(trimToNull(changes.getPhone()));
        customer.setEmail(trimToNull(changes.getEmail()));
        mapper.updateById(customer);
        return R.ok();
    }

    @GetMapping("/{id}/conversations")
    public R<Page<BotConversation>> conversations(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        BotCustomer customer = mapper.selectById(id);
        if (customer == null) return R.ok(new Page<>(page, size));
        return R.ok(conversationMapper.selectPage(
            new Page<>(page, size),
            new LambdaQueryWrapper<BotConversation>()
                .eq(BotConversation::getChannelType, customer.getChannelType())
                .eq(BotConversation::getChannelUserId, customer.getChannelUserId())
                .orderByDesc(BotConversation::getUpdateTime)));
    }

    @GetMapping("/by-channel")
    public R<BotCustomer> findByChannel(
            @RequestParam String channelType,
            @RequestParam String channelUserId) {
        return R.ok(mapper.selectOne(new LambdaQueryWrapper<BotCustomer>()
            .eq(BotCustomer::getChannelType, channelType)
            .eq(BotCustomer::getChannelUserId, channelUserId)));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
