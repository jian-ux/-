package com.feisheng.bot.gateway.service;

import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChannelUserProfileServiceTest {
    @Test
    void storesTrimmedChannelNickname() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChannelUserProfileService service = new ChannelUserProfileService(jdbcTemplate);
        ChannelMessageDTO message = new ChannelMessageDTO();
        message.setChannelType("dingtalk");
        message.setChannelUserId("user-1");
        message.setSenderName(" 张三 ");

        service.upsert(message);

        verify(jdbcTemplate).update(contains("INSERT INTO bot_channel_user"),
            eq("dingtalk"), eq("user-1"), eq("张三"), isNull());
        verify(jdbcTemplate).update(contains("INSERT INTO bot_customer"),
            eq("dingtalk"), eq("user-1"), eq("张三"), isNull());
    }

    @Test
    void refreshesConversationCountForCustomer() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChannelUserProfileService service = new ChannelUserProfileService(jdbcTemplate);
        ChannelMessageDTO message = new ChannelMessageDTO();
        message.setChannelType(" web ");
        message.setChannelUserId(" customer-1 ");

        service.refreshConversationStats(message);

        verify(jdbcTemplate).update(contains("SET customer.total_conversations"),
            eq("web"), eq("customer-1"));
    }

    @Test
    void ignoresMessageWithoutChannelIdentity() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChannelUserProfileService service = new ChannelUserProfileService(jdbcTemplate);

        service.upsert(new ChannelMessageDTO());

        verify(jdbcTemplate, never()).update(contains("INSERT"),
            org.mockito.ArgumentMatchers.<Object[]>any());
    }
}
