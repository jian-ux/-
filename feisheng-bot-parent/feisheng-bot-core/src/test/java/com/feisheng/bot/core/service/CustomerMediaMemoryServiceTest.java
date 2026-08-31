package com.feisheng.bot.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.entity.BotCustomer;
import com.feisheng.bot.core.entity.BotCustomerMedia;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotCustomerMapper;
import com.feisheng.bot.core.mapper.BotCustomerMediaMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerMediaMemoryServiceTest {
    @Test
    void storesImageMetadataAsUntrustedCustomerMedia() {
        BotCustomerMapper customerMapper = mock(BotCustomerMapper.class);
        BotCustomerMediaMapper mediaMapper = mock(BotCustomerMediaMapper.class);
        BotCustomer customer = new BotCustomer();
        customer.setId(9L);
        when(customerMapper.selectOne(any())).thenReturn(customer);
        when(mediaMapper.selectOne(any())).thenReturn(null);
        BotMessage message = new BotMessage();
        message.setId(31L);
        message.setContentType("image");
        message.setMetadata("{\"objectKey\":\"chat/31.png\",\"ocrText\":\"客户截图\"}");

        boolean stored = new CustomerMediaMemoryService(
            customerMapper, mediaMapper, new ObjectMapper())
            .saveFromMessage("web", "user-9", message);

        assertTrue(stored);
        org.mockito.ArgumentCaptor<BotCustomerMedia> captor =
            org.mockito.ArgumentCaptor.forClass(BotCustomerMedia.class);
        verify(mediaMapper).insert(captor.capture());
        assertTrue("UNTRUSTED".equals(captor.getValue().getTrustLevel()));
    }

    @Test
    void excludesPlaygroundImagesAndNonMediaMessages() {
        BotCustomerMapper customerMapper = mock(BotCustomerMapper.class);
        BotCustomerMediaMapper mediaMapper = mock(BotCustomerMediaMapper.class);
        CustomerMediaMemoryService service = new CustomerMediaMemoryService(
            customerMapper, mediaMapper, new ObjectMapper());
        BotMessage text = new BotMessage();
        text.setId(32L);
        text.setContentType("text");

        assertFalse(service.saveFromMessage("playground", "trial", text));
        assertFalse(service.saveFromMessage("web", "user", text));
        verify(mediaMapper, never()).insert(any(BotCustomerMedia.class));
    }
}
