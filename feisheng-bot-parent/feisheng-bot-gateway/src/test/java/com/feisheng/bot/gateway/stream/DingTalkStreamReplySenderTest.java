package com.feisheng.bot.gateway.stream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkStreamReplySenderTest {

    @Test
    void retriesTransientIoFailureUntilReplySucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        DingTalkStreamReplySender sender = new DingTalkStreamReplySender(delay -> {});

        sender.retryTransient(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ConnectException("connection refused");
            }
        });

        assertEquals(3, attempts.get());
    }

    @Test
    void propagatesIoFailureAfterMaximumAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        DingTalkStreamReplySender sender = new DingTalkStreamReplySender(delay -> {});

        assertThrows(ConnectException.class, () -> sender.retryTransient(() -> {
            attempts.incrementAndGet();
            throw new ConnectException("connection refused");
        }));

        assertEquals(3, attempts.get());
    }

    @Test
    void preservesInterruptStatusWhenRetryDelayIsInterrupted() {
        DingTalkStreamReplySender sender = new DingTalkStreamReplySender(delay -> {
            throw new InterruptedException("stop");
        });

        try {
            IOException failure = assertThrows(IOException.class,
                () -> sender.retryTransient(() -> {
                    throw new ConnectException("connection refused");
                }));

            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(failure.getCause() instanceof InterruptedException);
        } finally {
            Thread.interrupted();
        }
    }
}
