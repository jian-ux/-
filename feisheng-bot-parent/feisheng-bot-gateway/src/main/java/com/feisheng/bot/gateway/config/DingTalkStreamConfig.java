package com.feisheng.bot.gateway.config;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.feisheng.bot.gateway.stream.DingTalkStreamCallbackListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "dingtalk.stream", name = "managed-by-database",
    havingValue = "false", matchIfMissing = true)
public class DingTalkStreamConfig {
    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamConfig.class);

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "dingtalk.stream", name = "enabled", havingValue = "true")
    public OpenDingTalkClient dingTalkStreamClient(
            DingTalkStreamClientFactory clientFactory,
            DingTalkStreamCallbackListener callbackListener,
            @Value("${dingtalk.stream.client-id:}") String clientId,
            @Value("${dingtalk.stream.client-secret:}") String clientSecret,
            @Value("${dingtalk.app-secret:}") String appSecret,
            @Value("${dingtalk.stream.consume-threads:4}") int consumeThreads) {
        String effectiveClientSecret = clientSecret == null || clientSecret.isBlank()
            ? appSecret : clientSecret;
        if (clientId == null || clientId.isBlank()
                || effectiveClientSecret == null || effectiveClientSecret.isBlank()) {
            throw new IllegalStateException(
                "DINGTALK_CLIENT_ID and DINGTALK_CLIENT_SECRET are required when Stream mode is enabled");
        }
        log.info("DingTalk Stream mode enabled; starting bot message subscription");
        return clientFactory.create(
            clientId, effectiveClientSecret, consumeThreads, callbackListener);
    }
}
