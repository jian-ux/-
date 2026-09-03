package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.client.AdminClient;
import com.feisheng.bot.core.client.LlmHttpClient;
import com.feisheng.bot.core.client.LlmRouter;
import com.feisheng.bot.core.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelServiceImplTest {

    @Mock
    private LlmRouter llmRouter;
    @Mock
    private AdminClient adminClient;
    @Mock
    private LlmHttpClient llmHttpClient;

    @Test
    void appliesLocalPolicyToOnlyTheRequestedExactJsonModel() {
        when(adminClient.getActiveModels()).thenReturn(List.of(
            Map.of("id", 5L, "status", 1, "apiUrl", "http://primary",
                "apiKey", "key-5", "modelName", "qwen-primary", "provider", "dashscope"),
            Map.of("id", 9L, "status", 1, "apiUrl", "http://other",
                "apiKey", "key-9", "modelName", "qwen-other", "provider", "dashscope")
        ));
        when(llmHttpClient.callJsonSchemaWithPolicy(eq("http://primary"), eq("key-5"),
            eq("qwen-primary"), anyString(), anyString(), eq("dashscope"), anyMap(),
            eq(8_000), eq(0))).thenReturn(new ChatResponse("{}", true));

        ChatResponse response = service().chatWithExactModelJsonWithPolicy("prompt", "system",
            5L, Map.of("type", "object"), 8_000, 0);

        assertTrue(response.isSuccess());
        verify(llmHttpClient).callJsonSchemaWithPolicy(eq("http://primary"), eq("key-5"),
            eq("qwen-primary"), eq("system"), eq("prompt"), eq("dashscope"), anyMap(),
            eq(8_000), eq(0));
        verify(llmHttpClient, never()).callJsonSchemaWithPolicy(eq("http://other"),
            anyString(), anyString(), anyString(), anyString(), anyString(), anyMap(),
            eq(8_000), eq(0));
        verify(llmRouter, never()).chat(anyString(), anyString(), anyString());
    }

    private AiModelServiceImpl service() {
        return new AiModelServiceImpl(llmRouter, adminClient, llmHttpClient);
    }
}
