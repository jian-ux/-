package com.feisheng.bot.core.service;

import java.util.List;
import java.util.Map;

/** Runtime safety rules supplied by the host application. */
public interface SafetyRuleProvider {
    List<Map<String, Object>> getEnabledRules();
}
