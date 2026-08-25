package com.feisheng.bot.gateway.client;

import com.feisheng.bot.core.service.impl.DialogServiceImpl;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CoreClient {
    private final DialogServiceImpl dialogService;

    public CoreClient(DialogServiceImpl ds) {
        this.dialogService = ds;
    }

    /** Direct call: send message to dialog engine (no longer HTTP) */
    public Map<String, Object> sendMessage(String channelType, String channelUserId, String text, String title) {
        return dialogService.send(channelType, channelUserId, text, title);
    }

    public Map<String, Object> sendMessage(String channelType, String channelUserId,
                                           String text, String title,
                                           String contentType, String metadata) {
        return dialogService.sendWithMessageMetadata(
            channelType, channelUserId, text, title, contentType, metadata);
    }
}
