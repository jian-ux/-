package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotAiModelConfig;
import com.feisheng.bot.admin.entity.BotForbiddenRule;
import com.feisheng.bot.admin.mapper.BotAiModelConfigMapper;
import com.feisheng.bot.admin.mapper.BotForbiddenRuleMapper;
import com.feisheng.bot.core.client.ModelConfigProvider;
import com.feisheng.bot.core.service.SafetyRuleProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DatabaseRuntimeConfigProvider implements ModelConfigProvider, SafetyRuleProvider {
    private final BotAiModelConfigMapper modelMapper;
    private final BotForbiddenRuleMapper ruleMapper;

    public DatabaseRuntimeConfigProvider(BotAiModelConfigMapper modelMapper,
                                         BotForbiddenRuleMapper ruleMapper) {
        this.modelMapper = modelMapper;
        this.ruleMapper = ruleMapper;
    }

    @Override
    public List<Map<String, Object>> getActiveModels() {
        return modelMapper.selectList(new LambdaQueryWrapper<BotAiModelConfig>()
                .eq(BotAiModelConfig::getStatus, 1)
                .orderByDesc(BotAiModelConfig::getIsDefault))
            .stream().map(this::toModelMap).toList();
    }

    @Override
    public List<Map<String, Object>> getEnabledRules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<BotForbiddenRule>()
                .eq(BotForbiddenRule::getIsEnabled, 1)
                .orderByAsc(BotForbiddenRule::getPriority))
            .stream().map(this::toRuleMap).toList();
    }

    private Map<String, Object> toModelMap(BotAiModelConfig model) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", model.getId());
        result.put("modelName", model.getModelName());
        result.put("provider", model.getProvider());
        result.put("apiUrl", model.getApiUrl());
        result.put("apiKey", model.getApiKey());
        result.put("modelType", model.getModelType());
        result.put("parameters", model.getParameters());
        result.put("status", model.getStatus());
        result.put("isDefault", model.getIsDefault());
        return result;
    }

    private Map<String, Object> toRuleMap(BotForbiddenRule rule) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleType", rule.getRuleType());
        result.put("pattern", rule.getPattern());
        result.put("isRegex", rule.getIsRegex());
        result.put("action", rule.getAction());
        result.put("replyText", rule.getReplyText());
        result.put("description", rule.getDescription());
        result.put("isEnabled", rule.getIsEnabled());
        result.put("priority", rule.getPriority());
        return result;
    }
}
