package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotReplyStrategy;
import com.feisheng.bot.admin.mapper.BotReplyStrategyMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/reply-strategy")
public class ReplyStrategyController {
    private final BotReplyStrategyMapper mapper;

    public ReplyStrategyController(BotReplyStrategyMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/list")
    public R<Page<BotReplyStrategy>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(mapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<BotReplyStrategy>().orderByAsc(BotReplyStrategy::getPriority)));
    }

    @PostMapping("/save")
    public R<Void> save(@RequestBody BotReplyStrategy strategy) {
        if (strategy.getId() != null) {
            mapper.updateById(strategy);
        } else {
            strategy.setStatus(1);
            mapper.insert(strategy);
        }
        return R.ok();
    }

    @PutMapping("/{id}/toggle")
    public R<Void> toggle(@PathVariable Long id) {
        BotReplyStrategy s = mapper.selectById(id);
        if (s != null) {
            s.setStatus(s.getStatus() == 1 ? 0 : 1);
            mapper.updateById(s);
        }
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return R.ok();
    }
}
