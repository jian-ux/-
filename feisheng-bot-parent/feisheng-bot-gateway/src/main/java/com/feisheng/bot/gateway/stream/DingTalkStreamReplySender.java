package com.feisheng.bot.gateway.stream;

import com.dingtalk.open.app.api.chatbot.BotReplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class DingTalkStreamReplySender {
    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamReplySender.class);
    private static final int MAX_TRANSIENT_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 250;

    private final Sleeper sleeper;

    public DingTalkStreamReplySender() {
        this(Thread::sleep);
    }

    DingTalkStreamReplySender(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    public void replyText(String sessionWebhook, String content) throws IOException {
        retryTransient(() -> BotReplier.fromWebhook(sessionWebhook).replyText(content));
    }

    public void replyMarkdown(String sessionWebhook, String title, String content)
            throws IOException {
        retryTransient(() ->
            BotReplier.fromWebhook(sessionWebhook).replyMarkdown(title, content));
    }

    void retryTransient(IoOperation operation) throws IOException {
        for (int attempt = 1; attempt <= MAX_TRANSIENT_ATTEMPTS; attempt++) {
            try {
                operation.run();
                return;
            } catch (IOException e) {
                if (attempt == MAX_TRANSIENT_ATTEMPTS) throw e;
                log.warn("DingTalk Stream reply attempt {}/{} failed with {}; retrying",
                    attempt, MAX_TRANSIENT_ATTEMPTS, e.getClass().getSimpleName());
                try {
                    sleeper.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("DingTalk Stream reply retry interrupted", interrupted);
                }
            }
        }
    }

    @FunctionalInterface
    interface IoOperation {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
