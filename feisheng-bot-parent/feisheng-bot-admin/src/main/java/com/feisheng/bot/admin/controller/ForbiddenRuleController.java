package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotForbiddenRule;
import com.feisheng.bot.admin.mapper.BotForbiddenRuleMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/rules")
public class ForbiddenRuleController {
    private final BotForbiddenRuleMapper mapper;

    public ForbiddenRuleController(BotForbiddenRuleMapper m) {
        mapper = m;
    }

    @GetMapping("/list")
    public R<Page<BotForbiddenRule>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "500") int size,
            @RequestParam(required = false) String ruleType) {
        LambdaQueryWrapper<BotForbiddenRule> q = new LambdaQueryWrapper<>();
        if (ruleType != null && !ruleType.isEmpty()) q.eq(BotForbiddenRule::getRuleType, ruleType);
        q.orderByAsc(BotForbiddenRule::getPriority);
        return R.ok(mapper.selectPage(new Page<>(page, size), q));
    }

    @PostMapping("/save")
    public R<Void> save(@RequestBody BotForbiddenRule rule) {
        if (rule.getId() != null) mapper.updateById(rule);
        else mapper.insert(rule);
        return R.ok();
    }

    @PutMapping("/save")
    public R<Void> savePut(@RequestBody BotForbiddenRule rule) {
        return save(rule);
    }

    @PutMapping("/{id}/toggle")
    public R<Void> toggle(@PathVariable Long id) {
        BotForbiddenRule rule = mapper.selectById(id);
        if (rule != null) {
            rule.setIsEnabled(rule.getIsEnabled() == 1 ? 0 : 1);
            mapper.updateById(rule);
        }
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return R.ok();
    }
}
