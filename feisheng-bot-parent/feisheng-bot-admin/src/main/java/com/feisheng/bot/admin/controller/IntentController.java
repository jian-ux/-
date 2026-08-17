package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotIntent;
import com.feisheng.bot.admin.mapper.BotIntentMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/intent")
public class IntentController {
    private final BotIntentMapper mapper;

    public IntentController(BotIntentMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/list")
    public R<Page<BotIntent>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String intentName) {
        LambdaQueryWrapper<BotIntent> q = new LambdaQueryWrapper<>();
        if (intentName != null && !intentName.isEmpty()) {
            q.like(BotIntent::getIntentName, intentName);
        }
        q.orderByDesc(BotIntent::getCreateTime);
        return R.ok(mapper.selectPage(new Page<>(page, size), q));
    }

    @PostMapping("/save")
    public R<Void> save(@RequestBody BotIntent intent) {
        if (!StringUtils.hasText(intent.getIntentName())) {
            return R.fail(400, "意图名称不能为空");
        }
        if (!StringUtils.hasText(intent.getIntentKeywords())) {
            return R.fail(400, "触发关键词不能为空");
        }
        if (!StringUtils.hasText(intent.getReplyTemplate())) {
            return R.fail(400, "回复模板不能为空");
        }
        intent.setIntentName(intent.getIntentName().trim());
        intent.setIntentKeywords(intent.getIntentKeywords().trim());
        intent.setReplyTemplate(intent.getReplyTemplate().trim());
        if (intent.getId() != null) {
            mapper.updateById(intent);
        } else {
            intent.setStatus(1);
            mapper.insert(intent);
        }
        return R.ok();
    }

    @PutMapping("/{id}/toggle")
    public R<Void> toggle(@PathVariable Long id) {
        BotIntent intent = mapper.selectById(id);
        if (intent != null) {
            intent.setStatus(intent.getStatus() == 1 ? 0 : 1);
            mapper.updateById(intent);
        }
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return R.ok();
    }

    @GetMapping("/all-enabled")
    public R<List<BotIntent>> allEnabled() {
        return R.ok(mapper.selectList(
                new LambdaQueryWrapper<BotIntent>()
                        .eq(BotIntent::getStatus, 1)
                        .orderByDesc(BotIntent::getCreateTime)));
    }
}
