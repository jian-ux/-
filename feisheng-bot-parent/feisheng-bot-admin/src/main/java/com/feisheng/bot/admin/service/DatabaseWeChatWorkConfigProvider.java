package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotChannelConfig;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import com.feisheng.bot.gateway.config.WeChatWorkConfigProvider;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/** Reads the enabled WeCom channel from the admin-managed channel table. */
@Service
public class DatabaseWeChatWorkConfigProvider implements WeChatWorkConfigProvider {
    private final BotChannelConfigMapper mapper;
    private final ObjectMapper objectMapper;

    public DatabaseWeChatWorkConfigProvider(BotChannelConfigMapper mapper,
                                            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Config> activeConfig() {
        BotChannelConfig channel = mapper.selectOne(new LambdaQueryWrapper<BotChannelConfig>()
            .eq(BotChannelConfig::getChannelType, "wechat")
            .eq(BotChannelConfig::getStatus, 1)
            .orderByDesc(BotChannelConfig::getId)
            .last("LIMIT 1"));
        if (channel == null) return Optional.empty();

        Map<String, Object> values = parse(channel.getConfigJson());
        return Optional.of(new Config(
            text(values.get("corpId")),
            text(values.get("corpSecret")),
            text(values.get("agentId")),
            text(values.get("callbackToken")),
            text(values.get("callbackAesKey"))));
    }

    private Map<String, Object> parse(String value) {
        if (value == null || value.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
