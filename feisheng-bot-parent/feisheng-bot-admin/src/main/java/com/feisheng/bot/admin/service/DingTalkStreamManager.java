package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotChannelConfig;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import com.feisheng.bot.common.exception.BusinessException;
import com.feisheng.bot.gateway.config.DingTalkStreamClientFactory;
import com.feisheng.bot.gateway.stream.DingTalkStreamCallbackListener;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

@Service
public class DingTalkStreamManager {
    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamManager.class);

    private final BotChannelConfigMapper mapper;
    private final ObjectMapper objectMapper;
    private final DingTalkStreamClientFactory clientFactory;
    private final DingTalkStreamCallbackListener callbackListener;
    private final int consumeThreads;
    private final boolean environmentEnabled;
    private final String environmentClientId;
    private final String environmentClientSecret;

    private OpenDingTalkClient activeClient;
    private Long activeConfigId;
    private String activeClientId;
    private String activeClientSecret;
    private long nextReconnectAtMillis;

    private static final long RECONNECT_INTERVAL_MILLIS = 60_000L;

    public DingTalkStreamManager(
            BotChannelConfigMapper mapper,
            ObjectMapper objectMapper,
            DingTalkStreamClientFactory clientFactory,
            DingTalkStreamCallbackListener callbackListener,
            @Value("${dingtalk.stream.consume-threads:4}") int consumeThreads,
            @Value("${dingtalk.stream.enabled:false}") boolean environmentEnabled,
            @Value("${dingtalk.stream.client-id:}") String environmentClientId,
            @Value("${dingtalk.stream.client-secret:${dingtalk.app-secret:}}") String environmentClientSecret) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clientFactory = clientFactory;
        this.callbackListener = callbackListener;
        this.consumeThreads = consumeThreads;
        this.environmentEnabled = environmentEnabled;
        this.environmentClientId = environmentClientId;
        this.environmentClientSecret = environmentClientSecret;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreConnection() {
        BotChannelConfig config = mapper.selectOne(
            new LambdaQueryWrapper<BotChannelConfig>()
                .eq(BotChannelConfig::getChannelType, "dingtalk")
                .eq(BotChannelConfig::getStatus, 1)
                .orderByDesc(BotChannelConfig::getId)
                .last("LIMIT 1"));
        if (config != null) {
            Map<String, Object> values = parse(config.getConfigJson());
            try {
                activate(config.getId(), firstText(values.get("clientId"), values.get("appKey")),
                    firstText(values.get("clientSecret"), values.get("appSecret")), true);
            } catch (Exception e) {
                log.error("Failed to restore DingTalk channel config id={}", config.getId(), e);
            }
            return;
        }
        if (environmentEnabled && hasText(environmentClientId)
                && hasText(environmentClientSecret)) {
            try {
                activate(null, environmentClientId, environmentClientSecret, true);
                log.info("DingTalk Stream connection restored from environment configuration");
            } catch (Exception e) {
                log.error("Failed to restore DingTalk environment connection", e);
            }
        }
    }

    public void validateCredentials(String clientId, String clientSecret) {
        requireCredentials(clientId, clientSecret);
        try {
            clientFactory.validateCredentials(clientId.trim(), clientSecret.trim());
        } catch (Exception e) {
            clearIfActiveCredentials(clientId, clientSecret);
            log.warn("DingTalk credential validation failed for clientId={}", mask(clientId));
            throw new BusinessException(400,
                "钉钉 Stream 鉴权失败，请确认应用凭证及机器人 Stream 模式配置");
        }
    }

    public synchronized void activate(Long configId, String clientId, String clientSecret,
                                      boolean validateCredentials) {
        requireCredentials(clientId, clientSecret);
        if (validateCredentials) {
            try {
                validateCredentials(clientId, clientSecret);
            } catch (RuntimeException e) {
                clearActiveConfig(configId);
                throw e;
            }
        }

        OpenDingTalkClient candidate = null;
        try {
            candidate = clientFactory.create(
                clientId.trim(), clientSecret.trim(), consumeThreads, callbackListener);
            candidate.start();
        } catch (Exception e) {
            if (candidate != null) stopQuietly(candidate);
            log.error("Failed to start DingTalk Stream connection for config id={}", configId, e);
            throw new BusinessException(502, "钉钉 Stream 连接启动失败，请稍后重试");
        }

        OpenDingTalkClient previous = activeClient;
        activeClient = candidate;
        activeConfigId = configId;
        activeClientId = clientId.trim();
        activeClientSecret = clientSecret.trim();
        nextReconnectAtMillis = 0L;
        stopQuietly(previous);
        log.info("DingTalk Stream connection active for config id={}", configId);
    }

    public synchronized void deactivate(Long configId) {
        if (activeClient == null || !Objects.equals(activeConfigId, configId)) return;
        OpenDingTalkClient previous = activeClient;
        activeClient = null;
        activeConfigId = null;
        activeClientId = null;
        activeClientSecret = null;
        stopQuietly(previous);
        log.info("DingTalk Stream connection stopped for config id={}", configId);
    }

    public synchronized boolean isConnected(Long configId) {
        return activeClient != null && Objects.equals(activeConfigId, configId);
    }

    @PreDestroy
    public synchronized void shutdown() {
        OpenDingTalkClient previous = activeClient;
        activeClient = null;
        activeConfigId = null;
        activeClientId = null;
        activeClientSecret = null;
        stopQuietly(previous);
    }

    /**
     * The Stream SDK keeps retrying its socket internally and does not expose a
     * disconnect callback. Revalidate the active credentials periodically so a
     * revoked credential or permanently failed connection cannot remain marked
     * as CONNECTED forever, then recover an enabled database configuration.
     */
    @Scheduled(fixedDelay = RECONNECT_INTERVAL_MILLIS, initialDelay = RECONNECT_INTERVAL_MILLIS)
    public void healthCheck() {
        Long configId;
        String clientId;
        String clientSecret;
        synchronized (this) {
            configId = activeConfigId;
            clientId = activeClientId;
            clientSecret = activeClientSecret;
        }

        if (hasText(clientId) && hasText(clientSecret)) {
            try {
                clientFactory.validateCredentials(clientId, clientSecret);
                return;
            } catch (Exception e) {
                log.warn("DingTalk Stream health check failed for config id={}; marking it disconnected",
                    configId, e);
                synchronized (this) {
                    if (Objects.equals(activeConfigId, configId)
                            && Objects.equals(activeClientId, clientId)
                            && Objects.equals(activeClientSecret, clientSecret)) {
                        OpenDingTalkClient previous = activeClient;
                        activeClient = null;
                        activeConfigId = null;
                        activeClientId = null;
                        activeClientSecret = null;
                        nextReconnectAtMillis = System.currentTimeMillis() + RECONNECT_INTERVAL_MILLIS;
                        stopQuietly(previous);
                    }
                }
            }
        }

        long now = System.currentTimeMillis();
        synchronized (this) {
            if (activeClient != null || now < nextReconnectAtMillis) return;
        }
        BotChannelConfig config = mapper.selectOne(
            new LambdaQueryWrapper<BotChannelConfig>()
                .eq(BotChannelConfig::getChannelType, "dingtalk")
                .eq(BotChannelConfig::getStatus, 1)
                .orderByDesc(BotChannelConfig::getId)
                .last("LIMIT 1"));
        try {
            if (config != null) {
                Map<String, Object> values = parse(config.getConfigJson());
                activate(config.getId(), firstText(values.get("clientId"), values.get("appKey")),
                    firstText(values.get("clientSecret"), values.get("appSecret")), true);
            } else if (environmentEnabled && hasText(environmentClientId)
                    && hasText(environmentClientSecret)) {
                activate(null, environmentClientId, environmentClientSecret, true);
            } else {
                return;
            }
        } catch (Exception e) {
            synchronized (this) {
                nextReconnectAtMillis = System.currentTimeMillis() + RECONNECT_INTERVAL_MILLIS;
            }
            log.warn("DingTalk Stream reconnect failed for config id={}",
                config == null ? null : config.getId(), e);
        }
    }

    private synchronized void clearIfActiveCredentials(String clientId, String clientSecret) {
        if (!Objects.equals(activeClientId, clientId == null ? null : clientId.trim())
                || !Objects.equals(activeClientSecret, clientSecret == null ? null : clientSecret.trim())) {
            return;
        }
        OpenDingTalkClient previous = activeClient;
        activeClient = null;
        activeConfigId = null;
        activeClientId = null;
        activeClientSecret = null;
        nextReconnectAtMillis = System.currentTimeMillis() + RECONNECT_INTERVAL_MILLIS;
        stopQuietly(previous);
    }

    private synchronized void clearActiveConfig(Long configId) {
        if (configId == null || !Objects.equals(activeConfigId, configId)) return;
        OpenDingTalkClient previous = activeClient;
        activeClient = null;
        activeConfigId = null;
        activeClientId = null;
        activeClientSecret = null;
        nextReconnectAtMillis = System.currentTimeMillis() + RECONNECT_INTERVAL_MILLIS;
        stopQuietly(previous);
    }

    private Map<String, Object> parse(String configJson) {
        if (!hasText(configJson)) return Collections.emptyMap();
        try {
            return objectMapper.readValue(configJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private void stopQuietly(OpenDingTalkClient client) {
        if (client == null) return;
        try {
            client.stop();
        } catch (Exception e) {
            log.warn("Failed to stop previous DingTalk Stream connection", e);
        }
    }

    private static void requireCredentials(String clientId, String clientSecret) {
        if (!hasText(clientId) || !hasText(clientSecret)) {
            throw new BusinessException(400, "启用钉钉渠道必须填写 Client ID 和 Client Secret");
        }
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) return value.toString().trim();
        }
        return null;
    }

    private static String mask(String value) {
        if (!hasText(value) || value.length() <= 6) return "***";
        return value.substring(0, 3) + "***" + value.substring(value.length() - 3);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
