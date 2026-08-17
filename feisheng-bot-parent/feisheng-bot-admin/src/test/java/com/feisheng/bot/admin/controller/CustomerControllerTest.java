package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.mapper.BotConversationMapper;
import com.feisheng.bot.admin.mapper.BotCustomerMapper;
import com.feisheng.bot.admin.service.CustomerProfileSyncService;
import com.feisheng.bot.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerControllerTest {
    @Test
    void syncEndpointUpdatesCustomerProfiles() throws Exception {
        BotCustomerMapper customerMapper = mock(BotCustomerMapper.class);
        BotConversationMapper conversationMapper = mock(BotConversationMapper.class);
        CustomerProfileSyncService syncService = mock(CustomerProfileSyncService.class);
        when(syncService.sync()).thenReturn(new CustomerProfileSyncService.SyncResult(2, 3, 5));
        CustomerController controller = new CustomerController(
            customerMapper, conversationMapper, syncService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/admin/customer/sync"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.channelProfiles").value(2))
            .andExpect(jsonPath("$.data.conversationProfiles").value(3))
            .andExpect(jsonPath("$.data.affectedRows").value(5));
        verify(syncService).sync();
    }

    @Test
    void unsupportedMethodUsesHttp405InsteadOfGeneric500() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("POST", List.of("GET")));

        assertEquals(405, response.getStatusCode().value());
        assertEquals(405, response.getBody().getCode());
        assertEquals("请求方法不支持", response.getBody().getMsg());
    }
}
