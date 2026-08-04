package com.feisheng.bot.core.client;

import java.util.List;
import java.util.Map;

/** Runtime model configuration supplied by the host application. */
public interface ModelConfigProvider {
    List<Map<String, Object>> getActiveModels();
}
