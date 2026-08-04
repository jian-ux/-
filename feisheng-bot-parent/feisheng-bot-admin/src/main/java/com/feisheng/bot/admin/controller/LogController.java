package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.entity.SysOperationLog;
import com.feisheng.bot.admin.mapper.SysOperationLogMapper;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/log")
public class LogController {
    private final SysOperationLogMapper mapper;
    public LogController(SysOperationLogMapper m) { mapper = m; }

    @GetMapping("/operation")
    public R<Page<SysOperationLog>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(mapper.selectPage(new Page<>(page, size),
            new LambdaQueryWrapper<SysOperationLog>().orderByDesc(SysOperationLog::getCreateTime)));
    }
}
