package com.feisheng.bot.admin.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerProfileSyncServiceTest {
    @Test
    void synchronizesChannelUsersAndConversationOnlyCustomers() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(contains("FROM bot_channel_user channel_user"))).thenReturn(3);
        when(jdbcTemplate.update(contains("FROM bot_conversation conversation"))).thenReturn(2);
        CustomerProfileSyncService service = new CustomerProfileSyncService(jdbcTemplate);

        CustomerProfileSyncService.SyncResult result = service.sync();

        assertEquals(3, result.channelProfiles());
        assertEquals(2, result.conversationProfiles());
        assertEquals(5, result.affectedRows());
        verify(jdbcTemplate).update(contains("FROM bot_channel_user channel_user"));
        verify(jdbcTemplate).update(contains("FROM bot_conversation conversation"));
        verify(jdbcTemplate).update(contains("channel_user.channel_type IN ('dingtalk', 'wechat')"));
        verify(jdbcTemplate).update(contains("conversation.channel_type IN ('dingtalk', 'wechat')"));
    }
}
