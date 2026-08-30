package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.dto.ChannelConfigRequest;
import com.feisheng.bot.admin.dto.ChannelConfigView;
import com.feisheng.bot.admin.dto.ChannelConnectionTestResult;
import com.feisheng.bot.admin.entity.BotChannelConfig;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import com.feisheng.bot.common.exception.BusinessException;
import com.feisheng.bot.gateway.client.WeChatWorkClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ChannelConfigService {
    private static final Set<String> SUPPORTED_TYPES =
        Set.of("web", "wechat", "dingtalk", "other");

    private final BotChannelConfigMapper mapper;
    private final ObjectMapper objectMapper;
    private final DingTalkStreamManager dingTalkStreamManager;
    private final WeChatWorkClient weChatWorkClient;

    public ChannelConfigService(BotChannelConfigMapper mapper, ObjectMapper objectMapper,
                                DingTalkStreamManager dingTalkStreamManager) {
        this(mapper, objectMapper, dingTalkStreamManager, null);
    }

    @Autowired
    public ChannelConfigService(BotChannelConfigMapper mapper, ObjectMapper objectMapper,
                                DingTalkStreamManager dingTalkStreamManager,
                                WeChatWorkClient weChatWorkClient) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.dingTalkStreamManager = dingTalkStreamManager;
        this.weChatWorkClient = weChatWorkClient;
    }

    @Transactional
    public ChannelConfigView save(ChannelConfigRequest request) {
        validateBase(request);
        BotChannelConfig existing = request.getId() == null
            ? null : requireConfig(request.getId());
        String channelType = normalize(request.getChannelType());
        if (existing != null && !channelType.equals(existing.getChannelType())) {
            throw new BusinessException(400, "渠道类型不允许修改");
        }
        int effectiveStatus = request.getStatus() == null ? 1 : request.getStatus();

        Map<String, Object> values = existing == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(parse(existing.getConfigJson()));
        applyStructuredValues(channelType, request, values);
        if ("wechat".equals(channelType) && effectiveStatus == 1) {
            validateActiveWeChat(values);
        }

        BotChannelConfig entity = existing == null ? new BotChannelConfig() : existing;
        entity.setChannelType(channelType);
        entity.setChannelName(request.getChannelName().trim());
        entity.setStatus(effectiveStatus);
        entity.setConfigJson(write(values));

        if (existing == null) mapper.insert(entity);
        else mapper.updateById(entity);

        if ("dingtalk".equals(channelType)) {
            applyDingTalkRuntime(entity, values);
        } else if ("wechat".equals(channelType)
                && Integer.valueOf(1).equals(entity.getStatus())) {
            mapper.update(null, new UpdateWrapper<BotChannelConfig>()
                .eq("channel_type", "wechat")
                .ne("id", entity.getId())
                .eq("status", 1)
                .set("status", 0));
        }
        return toView(entity);
    }

    public Page<ChannelConfigView> list(int page, int size, String channelType) {
        LambdaQueryWrapper<BotChannelConfig> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(channelType)) {
            query.eq(BotChannelConfig::getChannelType, normalize(channelType));
        }
        query.orderByDesc(BotChannelConfig::getCreateTime);
        Page<BotChannelConfig> result = mapper.selectPage(new Page<>(page, size), query);
        List<ChannelConfigView> records = result.getRecords().stream()
            .map(this::toView)
            .toList();
        Page<ChannelConfigView> views = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        views.setRecords(records);
        return views;
    }

    public ChannelConfigView get(Long id) {
        return toView(requireConfig(id));
    }

    @Transactional
    public void delete(Long id) {
        BotChannelConfig config = requireConfig(id);
        mapper.deleteById(id);
        if ("dingtalk".equals(config.getChannelType())) {
            dingTalkStreamManager.deactivate(id);
        }
    }

    public ChannelConnectionTestResult test(Long id) {
        BotChannelConfig config = requireConfig(id);
        if ("wechat".equals(config.getChannelType())) {
            Map<String, Object> values = parse(config.getConfigJson());
            String corpId = firstText(values.get("corpId"));
            String corpSecret = firstText(values.get("corpSecret"));
            String agentId = firstText(values.get("agentId"));
            if (!hasText(corpId) || !hasText(corpSecret) || !hasText(agentId)) {
                return new ChannelConnectionTestResult(
                    false, "INVALID", "企业微信 CorpId、应用密钥和应用标识必须完整");
            }
            try {
                Long.parseLong(agentId);
            } catch (NumberFormatException e) {
                return new ChannelConnectionTestResult(
                    false, "INVALID", "企业微信应用标识必须是数字");
            }
            weChatWorkClient.testConnection(corpId, corpSecret);
            boolean callbackReady = hasText(firstText(values.get("callbackToken")))
                && hasText(firstText(values.get("callbackAesKey")));
            return new ChannelConnectionTestResult(true, "CONNECTED",
                callbackReady ? "企业微信凭证和回调配置验证成功"
                    : "企业微信 API 凭证验证成功，但回调 Token/AESKey 尚未配置");
        }
        if (!"dingtalk".equals(config.getChannelType())) {
            return new ChannelConnectionTestResult(
                true, "CONFIGURED", "该渠道配置已保存，无需建立连接");
        }
        Map<String, Object> values = parse(config.getConfigJson());
        String clientId = firstText(values.get("clientId"), values.get("appKey"));
        String clientSecret = firstText(values.get("clientSecret"), values.get("appSecret"));
        try {
            dingTalkStreamManager.validateCredentials(clientId, clientSecret);
        } catch (BusinessException e) {
            // A config may still have an older client in memory after its
            // credentials were edited. Do not report that stale client as connected.
            dingTalkStreamManager.deactivate(id);
            throw e;
        }
        if (Integer.valueOf(1).equals(config.getStatus())) {
            dingTalkStreamManager.activate(id, clientId, clientSecret, false);
            return new ChannelConnectionTestResult(true, "CONNECTED", "钉钉 Stream 已连接");
        }
        return new ChannelConnectionTestResult(
            true, "VERIFIED", "钉钉凭证验证成功，启用渠道后将建立 Stream 连接");
    }

    private void applyDingTalkRuntime(BotChannelConfig entity, Map<String, Object> values) {
        if (!Integer.valueOf(1).equals(entity.getStatus())) {
            dingTalkStreamManager.deactivate(entity.getId());
            return;
        }
        String clientId = firstText(values.get("clientId"), values.get("appKey"));
        String clientSecret = firstText(values.get("clientSecret"), values.get("appSecret"));
        dingTalkStreamManager.activate(entity.getId(), clientId, clientSecret, true);
        mapper.update(null, new UpdateWrapper<BotChannelConfig>()
            .eq("channel_type", "dingtalk")
            .ne("id", entity.getId())
            .eq("status", 1)
            .set("status", 0));
    }

    private void validateBase(ChannelConfigRequest request) {
        if (request == null) throw new BusinessException(400, "渠道配置不能为空");
        String channelType = normalize(request.getChannelType());
        if (!SUPPORTED_TYPES.contains(channelType)) {
            throw new BusinessException(400, "请选择有效的渠道类型");
        }
        if (!StringUtils.hasText(request.getChannelName())) {
            throw new BusinessException(400, "请输入渠道名称");
        }
        if (request.getChannelName().trim().length() > 100) {
            throw new BusinessException(400, "渠道名称不能超过 100 个字符");
        }
        if (request.getStatus() != null && request.getStatus() != 0 && request.getStatus() != 1) {
            throw new BusinessException(400, "渠道状态无效");
        }
    }

    private void applyStructuredValues(String channelType, ChannelConfigRequest request,
                                       Map<String, Object> values) {
        switch (channelType) {
            case "dingtalk" -> {
                values.put("connectionMode", "stream");
                putText(values, "clientId", request.getClientId(), false);
                putText(values, "clientSecret", request.getClientSecret(), true);
                putText(values, "robotCode", request.getRobotCode(), false);
            }
            case "wechat" -> {
                putText(values, "corpId", request.getCorpId(), false);
                putText(values, "corpSecret", request.getCorpSecret(), true);
                putText(values, "agentId", request.getAgentId(), false);
                putText(values, "callbackToken", request.getCallbackToken(), true);
                putText(values, "callbackAesKey", request.getCallbackAesKey(), true);
            }
            case "other" -> {
                putText(values, "endpoint", request.getEndpoint(), false);
                putText(values, "accessToken", request.getAccessToken(), true);
            }
            default -> values.clear();
        }
    }

    private void validateActiveWeChat(Map<String, Object> values) {
        if (!hasText(firstText(values.get("corpId")))
                || !hasText(firstText(values.get("corpSecret")))
                || !hasText(firstText(values.get("agentId")))) {
            throw new BusinessException(400, "企业微信 CorpId、应用密钥和应用标识不能为空");
        }
        if (!hasText(firstText(values.get("callbackToken")))
                || !hasText(firstText(values.get("callbackAesKey")))) {
            throw new BusinessException(400, "企业微信回调 Token 和 EncodingAESKey 不能为空");
        }
        try {
            Long.parseLong(firstText(values.get("agentId")));
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "企业微信应用标识必须是数字");
        }
    }

    private ChannelConfigView toView(BotChannelConfig config) {
        Map<String, Object> values = parse(config.getConfigJson());
        ChannelConfigView view = new ChannelConfigView();
        view.setId(config.getId());
        view.setChannelType(config.getChannelType());
        view.setChannelName(config.getChannelName());
        view.setStatus(config.getStatus());
        view.setCreateTime(config.getCreateTime());
        view.setUpdateTime(config.getUpdateTime());

        view.setConnectionMode(firstText(values.get("connectionMode"), "stream"));
        view.setClientId(firstText(values.get("clientId"), values.get("appKey")));
        view.setRobotCode(firstText(values.get("robotCode")));
        view.setClientSecretConfigured(hasText(firstText(
            values.get("clientSecret"), values.get("appSecret"))));
        view.setCorpId(firstText(values.get("corpId")));
        view.setAgentId(firstText(values.get("agentId")));
        view.setCorpSecretConfigured(hasText(firstText(values.get("corpSecret"))));
        view.setCallbackTokenConfigured(hasText(firstText(values.get("callbackToken"))));
        view.setCallbackAesKeyConfigured(hasText(firstText(values.get("callbackAesKey"))));
        view.setEndpoint(firstText(values.get("endpoint")));
        view.setAccessTokenConfigured(hasText(firstText(values.get("accessToken"))));
        view.setConfigSummary(summary(config.getChannelType(), view));
        view.setConnectionStatus(connectionStatus(config));
        return view;
    }

    private String connectionStatus(BotChannelConfig config) {
        if (!Integer.valueOf(1).equals(config.getStatus())) return "DISABLED";
        if (!"dingtalk".equals(config.getChannelType())) return "ENABLED";
        return dingTalkStreamManager.isConnected(config.getId()) ? "CONNECTED" : "NOT_CONNECTED";
    }

    private String summary(String channelType, ChannelConfigView view) {
        return switch (channelType) {
            case "dingtalk" -> view.isClientSecretConfigured()
                ? "Stream 模式 · 应用凭证已配置" : "Stream 模式 · 待补充应用凭证";
            case "wechat" -> view.isCorpSecretConfigured()
                ? (view.isCallbackTokenConfigured() && view.isCallbackAesKeyConfigured()
                    ? "企业凭证和回调配置已配置" : "企业凭证已配置，待补充回调配置")
                : "待补充企业凭证";
            case "other" -> hasText(view.getEndpoint())
                ? "服务地址已配置" : "未配置服务地址";
            default -> "无需额外配置";
        };
    }

    private BotChannelConfig requireConfig(Long id) {
        BotChannelConfig config = id == null ? null : mapper.selectById(id);
        if (config == null) throw new BusinessException(404, "渠道配置不存在");
        return config;
    }

    private Map<String, Object> parse(String configJson) {
        if (!hasText(configJson)) return Collections.emptyMap();
        try {
            return objectMapper.readValue(configJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private String write(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new BusinessException(500, "渠道配置保存失败");
        }
    }

    private void putText(Map<String, Object> values, String key, String value,
                         boolean preserveWhenBlank) {
        if (hasText(value)) {
            values.put(key, value.trim());
        } else if (!preserveWhenBlank) {
            values.remove(key);
        }
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) return value.toString().trim();
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
