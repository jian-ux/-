package com.feisheng.bot.gateway.stream;

import com.dingtalk.open.app.api.chatbot.BotReplier;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class DingTalkStreamReplySender {

    public void replyText(String sessionWebhook, String content) throws IOException {
        BotReplier.fromWebhook(sessionWebhook).replyText(content);
    }

    public void replyMarkdown(String sessionWebhook, String title, String content)
            throws IOException {
        BotReplier.fromWebhook(sessionWebhook).replyMarkdown(title, content);
    }
}
