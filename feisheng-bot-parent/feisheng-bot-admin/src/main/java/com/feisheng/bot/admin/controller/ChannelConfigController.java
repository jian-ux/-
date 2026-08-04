package com.feisheng.bot.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.admin.dto.ChannelConfigRequest;
import com.feisheng.bot.admin.dto.ChannelConfigView;
import com.feisheng.bot.admin.dto.ChannelConnectionTestResult;
import com.feisheng.bot.admin.service.ChannelConfigService;
import com.feisheng.bot.common.vo.R;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/channel/config")
public class ChannelConfigController {
    private final ChannelConfigService service;

    public ChannelConfigController(ChannelConfigService service) {
        this.service = service;
    }

    @RequestMapping(value = "/save", method = {RequestMethod.POST, RequestMethod.PUT})
    public R<ChannelConfigView> save(@RequestBody ChannelConfigRequest request) {
        return R.ok(service.save(request));
    }

    @GetMapping("/list")
    public R<Page<ChannelConfigView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String channelType) {
        return R.ok(service.list(page, size, channelType));
    }

    @GetMapping("/{id}")
    public R<ChannelConfigView> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping("/{id}/test")
    public R<ChannelConnectionTestResult> test(@PathVariable Long id) {
        return R.ok(service.test(id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
