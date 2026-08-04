package com.feisheng.bot.gateway.service.impl;
import com.feisheng.bot.common.util.RedisUtil;
import com.feisheng.bot.gateway.client.CoreClient;
import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.service.ChannelUserProfileService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
@Service
public class ChannelServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(ChannelServiceImpl.class);
    private final CoreClient coreClient;
    private final RedisUtil redisUtil;
    private final ChannelUserProfileService channelUserProfileService;
    public ChannelServiceImpl(CoreClient cc, RedisUtil ru,
                              ChannelUserProfileService channelUserProfileService) {
        coreClient=cc; redisUtil=ru; this.channelUserProfileService=channelUserProfileService;
    }
    public Map<String,Object> processMessage(ChannelMessageDTO dto) {
        return processMessage(dto, null);
    }

    public Map<String,Object> processMessage(ChannelMessageDTO dto,
                                             Supplier<String> contentSupplier) {
        if (dto == null || !StringUtils.hasText(dto.getChannelType())
                || !StringUtils.hasText(dto.getChannelUserId())
                || !StringUtils.hasText(dto.getMsgId())
                || (!StringUtils.hasText(dto.getContent()) && contentSupplier == null)) {
            throw new IllegalArgumentException(
                "channelType, channelUserId, msgId and content are required");
        }
        try {
            channelUserProfileService.upsert(dto);
        } catch (RuntimeException e) {
            log.warn("Could not update channel user profile for {}:{}: {}",
                dto.getChannelType(), dto.getChannelUserId(), e.getMessage());
        }
        String dedupKey = "msg:dedup:" + dto.getChannelType() + ":" + dto.getMsgId();
        if (!redisUtil.setnx(dedupKey, "processing", 24, TimeUnit.HOURS)) {
            Map<String,Object> m = new java.util.HashMap<>();
            m.put("duplicate", true); return m;
        }
        try {
            if (!StringUtils.hasText(dto.getContent()) && contentSupplier != null) {
                dto.setContent(contentSupplier.get());
            }
            if (!StringUtils.hasText(dto.getContent())) {
                throw new IllegalArgumentException("message content is empty after normalization");
            }
            Map<String, Object> result = coreClient.sendMessage(
                dto.getChannelType(), dto.getChannelUserId(), dto.getContent(), null);
            try {
                channelUserProfileService.refreshConversationStats(dto);
            } catch (RuntimeException e) {
                log.warn("Could not refresh customer conversation statistics for {}:{}: {}",
                    dto.getChannelType(), dto.getChannelUserId(), e.getMessage());
            }
            try {
                redisUtil.setex(dedupKey, "done", 24, TimeUnit.HOURS);
            } catch (RuntimeException e) {
                log.warn("Could not mark message {} as completed: {}", dto.getMsgId(), e.getMessage());
            }
            return result;
        } catch (RuntimeException e) {
            try {
                redisUtil.del(dedupKey);
            } catch (RuntimeException cleanupError) {
                log.warn("Could not release failed message {}: {}", dto.getMsgId(), cleanupError.getMessage());
            }
            throw e;
        }
    }
}
