package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.entity.BotCustomer;
import com.feisheng.bot.admin.mapper.BotConversationMapper;
import com.feisheng.bot.admin.mapper.BotCustomerMapper;
import com.feisheng.bot.admin.service.CustomerProfileSyncService;
import com.feisheng.bot.common.exception.BusinessException;
import com.feisheng.bot.common.vo.R;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/customer")
public class CustomerController {
    private static final String PLAYGROUND_CHANNEL = "playground";
    private final BotCustomerMapper mapper;
    private final BotConversationMapper conversationMapper;
    private final CustomerProfileSyncService profileSyncService;

    public CustomerController(BotCustomerMapper mapper,
                              BotConversationMapper conversationMapper,
                              CustomerProfileSyncService profileSyncService) {
        this.mapper = mapper;
        this.conversationMapper = conversationMapper;
        this.profileSyncService = profileSyncService;
    }

    @GetMapping("/list")
    public R<Page<BotCustomer>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String channelType) {
        LambdaQueryWrapper<BotCustomer> q = new LambdaQueryWrapper<>();
        q.apply("LOWER(TRIM(channel_type)) <> {0}", PLAYGROUND_CHANNEL);
        if (StringUtils.hasText(keyword)) {
            String normalizedKeyword = keyword.trim();
            q.and(w -> w.like(BotCustomer::getName, normalizedKeyword)
                .or().like(BotCustomer::getPhone, normalizedKeyword)
                .or().like(BotCustomer::getEmail, normalizedKeyword)
                .or().like(BotCustomer::getRemark, normalizedKeyword)
                .or().like(BotCustomer::getNickname, normalizedKeyword)
                .or().like(BotCustomer::getChannelUserId, normalizedKeyword));
        }
        if (StringUtils.hasText(channelType)) {
            q.eq(BotCustomer::getChannelType, channelType.trim().toLowerCase());
        }
        q.orderByDesc(BotCustomer::getLastContactTime);
        return R.ok(mapper.selectPage(
            new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)), q));
    }

    @PostMapping("/sync")
    public R<CustomerProfileSyncService.SyncResult> sync() {
        return R.ok(profileSyncService.sync());
    }

    @GetMapping("/{id}")
    public R<BotCustomer> detail(@PathVariable Long id) {
        return R.ok(visibleCustomer(id));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody BotCustomer changes) {
        BotCustomer customer = visibleCustomer(id);
        if (customer == null) return R.fail(404, "客户不存在");
        customer.setName(trimToNull(changes.getName()));
        customer.setPhone(trimToNull(changes.getPhone()));
        customer.setEmail(trimToNull(changes.getEmail()));
        String remark = trimToNull(changes.getRemark());
        if (remark != null && remark.length() > 500) {
            throw new BusinessException(400, "客户备注不能超过 500 个字符");
        }
        customer.setRemark(remark);
        mapper.updateById(customer);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        BotCustomer customer = visibleCustomer(id);
        if (customer == null) return R.fail(404, "客户不存在");
        mapper.deleteById(customer.getId());
        return R.ok();
    }

    @GetMapping("/{id}/conversations")
    public R<Page<BotConversation>> conversations(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        BotCustomer customer = mapper.selectById(id);
        if (!isVisibleCustomer(customer)) return R.ok(new Page<>(page, size));
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
        if (PLAYGROUND_CHANNEL.equalsIgnoreCase(channelType.trim())) {
            return R.ok(null);
        }
        return R.ok(mapper.selectOne(new LambdaQueryWrapper<BotCustomer>()
            .eq(BotCustomer::getChannelType, channelType)
            .eq(BotCustomer::getChannelUserId, channelUserId)));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private BotCustomer visibleCustomer(Long id) {
        BotCustomer customer = mapper.selectById(id);
        return isVisibleCustomer(customer) ? customer : null;
    }

    private boolean isVisibleCustomer(BotCustomer customer) {
        return customer != null
            && !PLAYGROUND_CHANNEL.equalsIgnoreCase(
                customer.getChannelType() == null ? null : customer.getChannelType().trim());
    }
}
