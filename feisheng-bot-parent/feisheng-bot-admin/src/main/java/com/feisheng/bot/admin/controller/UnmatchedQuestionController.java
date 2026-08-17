package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.BotUnmatchedQuestion;
import com.feisheng.bot.admin.mapper.BotUnmatchedQuestionMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/unmatched")
public class UnmatchedQuestionController {
    private final BotUnmatchedQuestionMapper mapper;

    public UnmatchedQuestionController(BotUnmatchedQuestionMapper m) { mapper = m; }

    @GetMapping("/list")
    public R<Page<BotUnmatchedQuestion>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(mapper.selectPage(new Page<>(page, size),
            new LambdaQueryWrapper<BotUnmatchedQuestion>()
                .orderByDesc(BotUnmatchedQuestion::getSimilarCount)
                .orderByDesc(BotUnmatchedQuestion::getCreateTime)));
    }

    @PutMapping("/{id}/resolve")
    public R<Void> resolve(@PathVariable Long id) {
        BotUnmatchedQuestion q = mapper.selectById(id);
        if (q != null) { q.setIsResolved(1); mapper.updateById(q); }
        return R.ok();
    }
}
