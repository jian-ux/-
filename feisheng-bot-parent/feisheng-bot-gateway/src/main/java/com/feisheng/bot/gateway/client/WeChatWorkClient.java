package com.feisheng.bot.gateway.client;

import com.feisheng.bot.common.util.RedisUtil;
import com.feisheng.bot.gateway.config.WeChatWorkConfigProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 企业微信 API 客户端
 */
@Component
public class WeChatWorkClient {
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private final RestTemplate rest;
    private final RedisUtil redisUtil;
    private final String configuredCorpId;
    private final String configuredCorpSecret;
    private final String configuredAgentId;
    private final String apiBase;
    private final ObjectProvider<WeChatWorkConfigProvider> configProvider;

    public WeChatWorkClient(
            RedisUtil redisUtil,
            @Value("${wecom.corp-id:}") String corpId,
            @Value("${wecom.corp-secret:}") String corpSecret,
            @Value("${wecom.agent-id:}") String agentId,
            @Value("${wecom.api-base:https://qyapi.weixin.qq.com}") String apiBase) {
        this(redisUtil, corpId, corpSecret, agentId, apiBase, new RestTemplate(), null);
    }

    WeChatWorkClient(RedisUtil redisUtil, String corpId, String corpSecret,
                     String agentId, String apiBase, RestTemplate rest) {
        this(redisUtil, corpId, corpSecret, agentId, apiBase, rest, null);
    }

    @Autowired
    public WeChatWorkClient(
            RedisUtil redisUtil,
            @Value("${wecom.corp-id:}") String corpId,
            @Value("${wecom.corp-secret:}") String corpSecret,
            @Value("${wecom.agent-id:}") String agentId,
            @Value("${wecom.api-base:https://qyapi.weixin.qq.com}") String apiBase,
            ObjectProvider<WeChatWorkConfigProvider> configProvider) {
        this(redisUtil, corpId, corpSecret, agentId, apiBase,
            new RestTemplate(), configProvider);
    }

    private WeChatWorkClient(RedisUtil redisUtil, String corpId, String corpSecret,
                             String agentId, String apiBase, RestTemplate rest,
                             ObjectProvider<WeChatWorkConfigProvider> configProvider) {
        this.redisUtil = redisUtil;
        this.configuredCorpId = corpId;
        this.configuredCorpSecret = corpSecret;
        this.configuredAgentId = agentId;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.rest = rest;
        this.configProvider = configProvider;
    }

    public String getAccessToken(String corpId, String corpSecret) {
        String cacheKey = "wx:token:" + corpId + ":"
            + Integer.toUnsignedString(corpSecret.hashCode(), 16);
        String cached = (String) redisUtil.get(cacheKey);
        if (cached != null) return cached;

        String url = apiBase + "/cgi-bin/gettoken?corpid=" + corpId + "&corpsecret=" + corpSecret;
        Map<String, Object> resp = rest.getForObject(url, Map.class);
        if (resp != null && resp.containsKey("access_token")) {
            String token = (String) resp.get("access_token");
            int expiresIn = resp.containsKey("expires_in") ? (int) resp.get("expires_in") : 7200;
            redisUtil.setex(cacheKey, token, expiresIn - 60, TimeUnit.SECONDS);
            return token;
        }
        throw new RuntimeException("Failed to get WeChat access_token: " + resp);
    }

    public boolean sendMessage(String userId, String content) {
        WeChatWorkConfigProvider.Config config = activeOrEnvironmentConfig();
        if (!config.hasApiCredentials()) {
            throw new IllegalStateException(
                "WECOM_CORP_ID, WECOM_CORP_SECRET and WECOM_AGENT_ID are required");
        }
        try {
            return sendMessage(config.corpId(), config.corpSecret(),
                Long.parseLong(config.agentId()), userId, content);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("WECOM_AGENT_ID must be a number", e);
        }
    }

    public boolean sendMessage(String corpId, String corpSecret, long agentId,
                               String userId, String content) {
        String token = getAccessToken(corpId, corpSecret);
        String url = apiBase + "/cgi-bin/message/send?access_token=" + token;

        Map<String, Object> body = new HashMap<>();
        body.put("touser", userId);
        body.put("msgtype", "text");
        body.put("agentid", agentId);
        Map<String, String> text = new HashMap<>();
        text.put("content", content);
        body.put("text", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        Map<String, Object> resp = rest.exchange(url, HttpMethod.POST, entity, Map.class).getBody();
        return resp != null && "0".equals(String.valueOf(resp.get("errcode")));
    }

    public boolean sendImage(String userId, byte[] image, String fileName, String contentType) {
        WeChatWorkConfigProvider.Config config = activeOrEnvironmentConfig();
        if (!config.hasApiCredentials()) {
            throw new IllegalStateException(
                "WECOM_CORP_ID, WECOM_CORP_SECRET and WECOM_AGENT_ID are required");
        }
        try {
            return sendImage(config.corpId(), config.corpSecret(),
                Long.parseLong(config.agentId()), userId, image, fileName, contentType);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("WECOM_AGENT_ID must be a number", e);
        }
    }

    public boolean sendImage(String corpId, String corpSecret, long agentId, String userId,
                             byte[] image, String fileName, String contentType) {
        validateImage(image, fileName, contentType);
        String token = getAccessToken(corpId, corpSecret);
        String mediaId = uploadImage(token, image, fileName, contentType);
        String url = apiBase + "/cgi-bin/message/send?access_token=" + token;

        Map<String, Object> body = new HashMap<>();
        body.put("touser", userId);
        body.put("msgtype", "image");
        body.put("agentid", agentId);
        body.put("image", Map.of("media_id", mediaId));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> response = rest.exchange(url, HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class).getBody();
        return response != null && "0".equals(String.valueOf(response.get("errcode")));
    }

    private String uploadImage(String token, byte[] image, String fileName, String contentType) {
        String url = apiBase + "/cgi-bin/media/upload?access_token=" + token + "&type=image";
        ByteArrayResource resource = new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return safeFileName(fileName);
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("media", new HttpEntity<>(resource, partHeaders));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        Map<String, Object> response = rest.exchange(url, HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class).getBody();
        Object mediaId = response == null ? null : response.get("media_id");
        if (mediaId == null || mediaId.toString().isBlank()) {
            throw new IllegalStateException("WeCom image upload did not return media_id: " + response);
        }
        return mediaId.toString();
    }

    private static void validateImage(byte[] image, String fileName, String contentType) {
        if (image == null || image.length == 0 || image.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("WeCom image must be between 1 byte and 10 MB");
        }
        if (isBlank(fileName) || isBlank(contentType) || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("WeCom image file name and content type are required");
        }
    }

    private static String safeFileName(String value) {
        return value.replace("\r", "_").replace("\n", "_").replace("\"", "_");
    }

    public boolean testConnection(String corpId, String corpSecret) {
        if (isBlank(corpId) || isBlank(corpSecret)) {
            throw new IllegalArgumentException("企业微信 CorpId 和应用密钥不能为空");
        }
        getAccessToken(corpId.trim(), corpSecret.trim());
        return true;
    }

    private WeChatWorkConfigProvider.Config activeOrEnvironmentConfig() {
        if (configProvider != null) {
            WeChatWorkConfigProvider provider = configProvider.getIfAvailable();
            if (provider != null) {
                WeChatWorkConfigProvider.Config active = provider.activeConfig().orElse(null);
                if (active != null && active.hasApiCredentials()) return active;
            }
        }
        return new WeChatWorkConfigProvider.Config(
            configuredCorpId, configuredCorpSecret, configuredAgentId, "", "");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
