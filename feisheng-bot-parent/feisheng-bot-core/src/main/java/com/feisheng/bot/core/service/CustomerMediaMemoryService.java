package com.feisheng.bot.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.entity.BotCustomer;
import com.feisheng.bot.core.entity.BotCustomerMedia;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotCustomerMapper;
import com.feisheng.bot.core.mapper.BotCustomerMediaMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Persists customer-scoped media metadata without indexing it as knowledge. */
@Service
public class CustomerMediaMemoryService {
    private final BotCustomerMapper customerMapper;
    private final BotCustomerMediaMapper mediaMapper;
    private final ObjectMapper objectMapper;

    public CustomerMediaMemoryService(BotCustomerMapper customerMapper,
                                      BotCustomerMediaMapper mediaMapper,
                                      ObjectMapper objectMapper) {
        this.customerMapper = customerMapper;
        this.mediaMapper = mediaMapper;
        this.objectMapper = objectMapper;
    }

    public boolean saveFromMessage(String channelType, String channelUserId, BotMessage message) {
        if (channelType == null || channelUserId == null || message == null
                || "playground".equalsIgnoreCase(channelType.trim())
                || message.getId() == null || !isImage(message)) return false;
        BotCustomer customer = customerMapper.selectOne(new LambdaQueryWrapper<BotCustomer>()
            .eq(BotCustomer::getChannelType, channelType.trim())
            .eq(BotCustomer::getChannelUserId, channelUserId.trim()).last("LIMIT 1"));
        if (customer == null) {
            customer = new BotCustomer();
            customer.setChannelType(channelType.trim());
            customer.setChannelUserId(channelUserId.trim());
            customerMapper.insert(customer);
        }
        if (customer.getId() == null) return false;
        BotCustomerMedia existing = mediaMapper.selectOne(new LambdaQueryWrapper<BotCustomerMedia>()
            .eq(BotCustomerMedia::getSourceMessageId, message.getId()).last("LIMIT 1"));
        if (existing != null) return false;
        Map<String, Object> metadata = metadata(message.getMetadata());
        BotCustomerMedia media = new BotCustomerMedia();
        media.setCustomerId(customer.getId());
        media.setSourceMessageId(message.getId());
        media.setMediaType(first(metadata, "mediaType", message.getContentType(), "image"));
        media.setObjectKey(first(metadata, "objectKey", null, null));
        media.setOcrText(first(metadata, "ocrText", null, null));
        media.setMetadata(message.getMetadata());
        media.setTrustLevel("UNTRUSTED");
        mediaMapper.insert(media);
        return true;
    }

    private boolean isImage(BotMessage message) {
        String type = message.getContentType();
        if (type != null && (type.equalsIgnoreCase("image") || type.equalsIgnoreCase("mixed"))) return true;
        return metadata(message.getMetadata()).containsKey("objectKey");
    }

    private Map<String, Object> metadata(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(value, new TypeReference<>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String first(Map<String, Object> metadata, String key, String fallback, String defaultValue) {
        Object value = metadata.get(key);
        if (value != null && !value.toString().isBlank()) return value.toString();
        if (fallback != null && !fallback.isBlank()) return fallback;
        return defaultValue;
    }
}
