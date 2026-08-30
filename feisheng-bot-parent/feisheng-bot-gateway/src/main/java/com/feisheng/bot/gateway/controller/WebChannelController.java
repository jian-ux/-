package com.feisheng.bot.gateway.controller;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.service.WebAsyncMessageService;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/gateway/channel")
public class WebChannelController {
    private final ChannelServiceImpl channelService;
    private final WebAsyncMessageService asyncMessageService;
    public WebChannelController(ChannelServiceImpl cs, WebAsyncMessageService asyncMessageService) {
        channelService=cs;
        this.asyncMessageService = asyncMessageService;
    }
    @PostMapping("/web/message")
    public R<Map<String,Object>> receiveWeb(@RequestBody ChannelMessageDTO dto) {
        dto.setChannelType("web");
        return R.ok(channelService.processMessage(dto));
    }

    @PostMapping("/web/message/async")
    public R<Map<String,Object>> receiveWebAsync(@RequestBody ChannelMessageDTO dto) {
        return R.ok(asyncMessageService.submit(dto));
    }

    @GetMapping("/web/message/async/{requestId}")
    public R<Map<String,Object>> webAsyncStatus(@PathVariable String requestId) {
        return R.ok(asyncMessageService.status(requestId));
    }
}
