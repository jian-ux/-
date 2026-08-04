package com.feisheng.bot.gateway.controller;
import com.feisheng.bot.common.vo.R;
import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/gateway/channel")
public class WebChannelController {
    private final ChannelServiceImpl channelService;
    public WebChannelController(ChannelServiceImpl cs) { channelService=cs; }
    @PostMapping("/web/message")
    public R<Map<String,Object>> receiveWeb(@RequestBody ChannelMessageDTO dto) {
        dto.setChannelType("web");
        return R.ok(channelService.processMessage(dto));
    }
}