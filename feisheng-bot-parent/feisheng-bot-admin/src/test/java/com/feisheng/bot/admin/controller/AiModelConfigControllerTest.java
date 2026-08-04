package com.feisheng.bot.admin.controller;

import com.feisheng.bot.admin.entity.BotAiModelConfig;
import com.feisheng.bot.admin.mapper.BotAiModelConfigMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelConfigControllerTest {
    @Test
    void enabledModelsIncludesOnlyLlmModelsInChatSelector() {
        BotAiModelConfigMapper mapper = mock(BotAiModelConfigMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
            model(1L, "deepseek-chat", "LLM"),
            model(2L, "embedding-3", "Embedding"),
            model(3L, "whisper-1", "Speech"),
            model(4L, "legacy-chat", null)));

        AiModelConfigController controller = new AiModelConfigController(mapper);

        List<BotAiModelConfig> enabled = controller.enabled().getData();

        assertEquals(2, enabled.size());
        assertEquals("deepseek-chat", enabled.get(0).getModelName());
        assertEquals("legacy-chat", enabled.get(1).getModelName());
    }

    private BotAiModelConfig model(Long id, String name, String type) {
        BotAiModelConfig model = new BotAiModelConfig();
        model.setId(id);
        model.setModelName(name);
        model.setModelType(type);
        model.setStatus(1);
        return model;
    }
}
