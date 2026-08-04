package com.feisheng.bot.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.mapper.BotConversationMapper;
import com.feisheng.bot.core.service.ConversationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {
    @Mock private BotConversationMapper mapper;

    @Test
    void reusesTransferredConversationInsteadOfCreatingAiConversation() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
            BotConversation.class);
        BotConversation transferred = new BotConversation();
        transferred.setId(12L);
        transferred.setStatus("transferred");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(transferred);
        ConversationServiceImpl service = new ConversationServiceImpl(mapper);

        BotConversation result = service.getOrCreate("web", "user-1", "咨询");

        assertSame(transferred, result);
        ArgumentCaptor<Wrapper<BotConversation>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("status IN"));
        verifyNoMoreInteractions(mapper);
    }
}
