package com.feisheng.bot.core.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.mapper.BotConversationMapper;
import org.springframework.stereotype.Service;
@Service
public class ConversationServiceImpl {
    private final BotConversationMapper mapper;
    public ConversationServiceImpl(BotConversationMapper m) { mapper=m; }
    public BotConversation getOrCreate(String channelType, String channelUserId, String title) {
        BotConversation cv = mapper.selectOne(new LambdaQueryWrapper<BotConversation>()
                .eq(BotConversation::getChannelType, channelType)
                .eq(BotConversation::getChannelUserId, channelUserId)
                .in(BotConversation::getStatus, "active", "transferred")
                .orderByDesc(BotConversation::getId)
                .last("LIMIT 1"));
        if (cv == null) {
            cv = new BotConversation();
            cv.setChannelType(channelType); cv.setChannelUserId(channelUserId);
            cv.setTitle(title != null ? title : "Conversation"); cv.setStatus("active");
            mapper.insert(cv);
        }
        return cv;
    }
    public void close(Long id) {
        BotConversation cv = mapper.selectById(id);
        if (cv != null) { cv.setStatus("closed"); mapper.updateById(cv); }
    }
    public BotConversation getById(Long id) { return mapper.selectById(id); }
    public void updateStatus(BotConversation cv) {
        mapper.updateById(cv);
    }
    public Page<BotConversation> list(int page, int size) {
        return mapper.selectPage(new Page<>(page,size), new LambdaQueryWrapper<BotConversation>().orderByDesc(BotConversation::getUpdateTime));
    }
}
