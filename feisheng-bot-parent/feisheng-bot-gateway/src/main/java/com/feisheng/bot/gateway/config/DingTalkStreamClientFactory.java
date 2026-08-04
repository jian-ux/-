package com.feisheng.bot.gateway.config;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.UserAgent;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.open.OpenApiClient;
import com.dingtalk.open.app.api.open.OpenApiClientBuilder;
import com.dingtalk.open.app.api.open.OpenConnectionRequest;
import com.dingtalk.open.app.api.open.OpenConnectionResponse;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.dingtalk.open.app.api.util.IpUtils;
import com.feisheng.bot.gateway.stream.DingTalkStreamCallbackListener;
import com.dingtalk.open.app.stream.network.core.Subscription;
import com.dingtalk.open.app.stream.protocol.CommandType;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class DingTalkStreamClientFactory {
    private static final String OPEN_API_HOST = "https://api.dingtalk.com";
    private static final int VALIDATION_TIMEOUT_MILLIS = 5000;

    public OpenDingTalkClient create(String clientId, String clientSecret, int consumeThreads,
                                     DingTalkStreamCallbackListener callbackListener) {
        return OpenDingTalkStreamClientBuilder.custom()
            .credential(new AuthClientCredential(clientId, clientSecret))
            .consumeThreads(consumeThreads)
            .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, callbackListener)
            .build();
    }

    public void validateCredentials(String clientId, String clientSecret) throws Exception {
        OpenConnectionRequest request = new OpenConnectionRequest();
        request.setClientId(clientId);
        request.setClientSecret(clientSecret);
        request.setUa(UserAgent.getUserAgent().getUa());
        request.setLocalIp(IpUtils.getLocalIP());
        request.setSubscriptions(Set.of(new Subscription(
            CommandType.CALLBACK, DingTalkStreamTopics.BOT_MESSAGE_TOPIC)));

        OpenApiClient openApiClient = OpenApiClientBuilder.create()
            .setHost(OPEN_API_HOST)
            .setTimeout(VALIDATION_TIMEOUT_MILLIS)
            .build();
        OpenConnectionResponse response = openApiClient.openConnection(request);
        if (response == null || isBlank(response.getEndpoint()) || isBlank(response.getTicket())) {
            throw new IllegalStateException("DingTalk returned an incomplete Stream connection");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
