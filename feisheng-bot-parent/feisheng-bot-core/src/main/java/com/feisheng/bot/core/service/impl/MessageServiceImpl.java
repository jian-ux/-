package com.feisheng.bot.core.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotMessageMapper;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
public class MessageServiceImpl {
    private final BotMessageMapper mapper;
    public MessageServiceImpl(BotMessageMapper m) { mapper=m; }
    public void save(BotMessage msg) { mapper.insert(msg); }
    public void updateMetadata(BotMessage msg, String metadata) {
        msg.setMetadata(metadata);
        mapper.updateById(msg);
    }
    public List<BotMessage> getByConversation(Long convId) {
        return mapper.selectList(new LambdaQueryWrapper<BotMessage>()
                .eq(BotMessage::getConversationId, convId)
                .orderByAsc(BotMessage::getCreateTime)
                .orderByAsc(BotMessage::getId));
    }
}
