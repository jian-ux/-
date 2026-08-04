package com.feisheng.bot.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.dto.ChannelConfigRequest;
import com.feisheng.bot.admin.dto.ChannelConfigView;
import com.feisheng.bot.admin.entity.BotChannelConfig;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelConfigServiceTest {
    @Mock private BotChannelConfigMapper mapper;
    @Mock private DingTalkStreamManager streamManager;

    private ObjectMapper objectMapper;
    private ChannelConfigService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ChannelConfigService(mapper, objectMapper, streamManager);
    }

    @Test
    void savesStructuredDingTalkConfigAndActivatesStream() throws Exception {
        doAnswer(invocation -> {
            BotChannelConfig config = invocation.getArgument(0);
            config.setId(42L);
            return 1;
        }).when(mapper).insert(any(BotChannelConfig.class));
        when(streamManager.isConnected(42L)).thenReturn(true);

        ChannelConfigRequest request = dingTalkRequest();
        ChannelConfigView result = service.save(request);

        ArgumentCaptor<BotChannelConfig> saved = ArgumentCaptor.forClass(BotChannelConfig.class);
        verify(mapper).insert(saved.capture());
        Map<String, Object> values = objectMapper.readValue(
            saved.getValue().getConfigJson(), new TypeReference<>() {});
        assertEquals("client-id", values.get("clientId"));
        assertEquals("client-secret", values.get("clientSecret"));
        assertEquals("robot-code", values.get("robotCode"));
        verify(streamManager).activate(42L, "client-id", "client-secret", true);
        assertTrue(result.isClientSecretConfigured());
        assertEquals("CONNECTED", result.getConnectionStatus());
    }

    @Test
    void keepsStoredSecretWhenEditLeavesSecretBlank() throws Exception {
        BotChannelConfig existing = config(7L, 0,
            "{\"connectionMode\":\"stream\",\"clientId\":\"old-id\","
                + "\"clientSecret\":\"stored-secret\"}");
        when(mapper.selectById(7L)).thenReturn(existing);

        ChannelConfigRequest request = dingTalkRequest();
        request.setId(7L);
        request.setStatus(0);
        request.setClientId("new-id");
        request.setClientSecret("");
        request.setRobotCode("");

        ChannelConfigView result = service.save(request);

        ArgumentCaptor<BotChannelConfig> saved = ArgumentCaptor.forClass(BotChannelConfig.class);
        verify(mapper).updateById(saved.capture());
        Map<String, Object> values = objectMapper.readValue(
            saved.getValue().getConfigJson(), new TypeReference<>() {});
        assertEquals("stored-secret", values.get("clientSecret"));
        assertEquals("new-id", values.get("clientId"));
        assertNull(values.get("robotCode"));
        assertTrue(result.isClientSecretConfigured());
        verify(streamManager).deactivate(7L);
        verify(streamManager, never()).activate(eq(7L), any(), any(), eq(true));
    }

    @Test
    void viewNeverReturnsStoredSecret() {
        BotChannelConfig existing = config(8L, 1,
            "{\"clientId\":\"visible-id\",\"clientSecret\":\"must-not-leak\"}");
        when(mapper.selectById(8L)).thenReturn(existing);
        when(streamManager.isConnected(8L)).thenReturn(false);

        ChannelConfigView result = service.get(8L);

        assertEquals("visible-id", result.getClientId());
        assertTrue(result.isClientSecretConfigured());
        assertFalse(result.toString().contains("must-not-leak"));
        assertEquals("NOT_CONNECTED", result.getConnectionStatus());
    }

    private static ChannelConfigRequest dingTalkRequest() {
        ChannelConfigRequest request = new ChannelConfigRequest();
        request.setChannelType("dingtalk");
        request.setChannelName("售后钉钉机器人");
        request.setStatus(1);
        request.setConnectionMode("stream");
        request.setClientId("client-id");
        request.setClientSecret("client-secret");
        request.setRobotCode("robot-code");
        return request;
    }

    private static BotChannelConfig config(Long id, int status, String configJson) {
        BotChannelConfig config = new BotChannelConfig();
        config.setId(id);
        config.setChannelType("dingtalk");
        config.setChannelName("钉钉机器人");
        config.setStatus(status);
        config.setConfigJson(configJson);
        return config;
    }
}
