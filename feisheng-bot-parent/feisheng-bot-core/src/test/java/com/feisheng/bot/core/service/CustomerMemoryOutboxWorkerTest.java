package com.feisheng.bot.core.service;

import com.feisheng.bot.core.entity.BotMemoryOutboxEvent;
import com.feisheng.bot.core.mapper.BotMemoryOutboxEventMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class CustomerMemoryOutboxWorkerTest {
    @Test
    void claimsAndCompletesAValidEvent() {
        BotMemoryOutboxEventMapper mapper = mock(BotMemoryOutboxEventMapper.class);
        BotMemoryOutboxEvent event = event("CONTEXT_SUMMARY", "{}");
        when(mapper.selectAvailable(anyInt())).thenReturn(List.of(event));
        when(mapper.claim(anyLong(), anyInt())).thenReturn(1);
        when(mapper.update(any(), any())).thenReturn(1);
        assertEquals(1, new CustomerMemoryOutboxWorker(mapper).processBatch(10));
        verify(mapper).claim(event.getId(), 60);
        verify(mapper).update(any(), any());
    }

    @Test
    void skipsRowsThatAnotherWorkerClaimed() {
        BotMemoryOutboxEventMapper mapper = mock(BotMemoryOutboxEventMapper.class);
        BotMemoryOutboxEvent event = event("CONTEXT_SUMMARY", "{}");
        when(mapper.selectAvailable(anyInt())).thenReturn(List.of(event));
        when(mapper.claim(anyLong(), anyInt())).thenReturn(0);
        assertEquals(0, new CustomerMemoryOutboxWorker(mapper).processBatch(1));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void retriesTransientFailureAndEventuallyMarksFailed() {
        BotMemoryOutboxEventMapper mapper = mock(BotMemoryOutboxEventMapper.class);
        BotMemoryOutboxEvent event = event("CONTEXT_SUMMARY", "{}");
        when(mapper.selectAvailable(anyInt())).thenReturn(List.of(event));
        when(mapper.claim(anyLong(), anyInt())).thenReturn(1);
        when(mapper.update(any(), any())).thenReturn(1);
        CustomerMemoryOutboxWorker worker = new CustomerMemoryOutboxWorker(mapper, 5, 2) {
            @Override protected void processEvent(BotMemoryOutboxEvent ignored) {
                throw new RuntimeException("token=secret-value");
            }
        };
        assertEquals(0, worker.processBatch(1));
        assertEquals(1, worker.retryCount());
        verify(mapper).update(any(), any());
    }

    private BotMemoryOutboxEvent event(String type, String payload) {
        BotMemoryOutboxEvent event = new BotMemoryOutboxEvent();
        event.setId(7L); event.setEventType(type); event.setPayload(payload);
        event.setAttempts(0); return event;
    }
}
