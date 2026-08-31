package com.feisheng.bot.core.service;

import com.feisheng.bot.core.entity.BotMemoryOutboxEvent;
import com.feisheng.bot.core.mapper.BotMemoryOutboxEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerMemoryOutboxServiceTest {
    @Test
    void deduplicatesByStableKeyAndRejectsUnknownTypes() {
        BotMemoryOutboxEventMapper mapper = mock(BotMemoryOutboxEventMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        CustomerMemoryOutboxService service = new CustomerMemoryOutboxService(mapper);
        BotMemoryOutboxEvent first = service.enqueue(CustomerMemoryOutboxService.PROFILE_AI_EXTRACTION,
            "msg:10:profile", 1L, 2L, 10L, "{\"text\":\"hello\"}");
        assertNotNull(first);
        assertEquals("PENDING", first.getStatus());
        verify(mapper).insert(any(BotMemoryOutboxEvent.class));

        BotMemoryOutboxEvent existing = new BotMemoryOutboxEvent();
        existing.setId(99L);
        when(mapper.selectOne(any())).thenReturn(existing);
        assertSame(existing, service.enqueue(CustomerMemoryOutboxService.PROFILE_AI_EXTRACTION,
            "msg:10:profile", 1L, 2L, 10L, "ignored"));
        assertNull(service.enqueue("UNKNOWN", "bad", 1L, 2L, 10L, "{}"));
        assertNull(service.enqueue(" ", "bad", 1L, 2L, 10L, "{}"));
        assertNull(service.enqueue(CustomerMemoryOutboxService.MEDIA_OCR_MEMORY,
            " ", 1L, 2L, 10L, "{}"));
    }
}
