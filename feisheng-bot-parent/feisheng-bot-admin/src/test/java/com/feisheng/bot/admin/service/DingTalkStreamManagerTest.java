package com.feisheng.bot.admin.service;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import com.feisheng.bot.common.exception.BusinessException;
import com.feisheng.bot.gateway.config.DingTalkStreamClientFactory;
import com.feisheng.bot.gateway.stream.DingTalkStreamCallbackListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DingTalkStreamManagerTest {
    @Mock private BotChannelConfigMapper mapper;
    @Mock private DingTalkStreamClientFactory clientFactory;
    @Mock private DingTalkStreamCallbackListener callbackListener;
    @Mock private OpenDingTalkClient firstClient;
    @Mock private OpenDingTalkClient secondClient;

    private DingTalkStreamManager manager;

    @BeforeEach
    void setUp() {
        manager = new DingTalkStreamManager(
            mapper, new ObjectMapper(), clientFactory, callbackListener,
            4, false, "", "");
    }

    @Test
    void startsNewClientBeforeStoppingPreviousClient() throws Exception {
        when(clientFactory.create("first-id", "first-secret", 4, callbackListener))
            .thenReturn(firstClient);
        when(clientFactory.create("second-id", "second-secret", 4, callbackListener))
            .thenReturn(secondClient);

        manager.activate(1L, "first-id", "first-secret", true);
        manager.activate(2L, "second-id", "second-secret", true);

        verify(clientFactory).validateCredentials("first-id", "first-secret");
        verify(clientFactory).validateCredentials("second-id", "second-secret");
        verify(firstClient).start();
        verify(secondClient).start();
        verify(firstClient).stop();
        assertFalse(manager.isConnected(1L));
        assertTrue(manager.isConnected(2L));
    }

    @Test
    void invalidCredentialsDoNotCreateOrReplaceClient() throws Exception {
        doThrow(new IllegalStateException("invalid"))
            .when(clientFactory).validateCredentials("bad-id", "bad-secret");

        assertThrows(BusinessException.class,
            () -> manager.activate(3L, "bad-id", "bad-secret", true));

        verify(clientFactory, never()).create(
            "bad-id", "bad-secret", 4, callbackListener);
        assertFalse(manager.isConnected(3L));
    }

    @Test
    void disablingActiveConfigStopsClient() throws Exception {
        when(clientFactory.create("client-id", "client-secret", 4, callbackListener))
            .thenReturn(firstClient);
        manager.activate(9L, "client-id", "client-secret", false);

        manager.deactivate(9L);

        verify(firstClient).stop();
        assertFalse(manager.isConnected(9L));
    }

    @Test
    void healthCheckClearsConnectionWhenCredentialsBecomeInvalid() throws Exception {
        when(clientFactory.create("client-id", "client-secret", 4, callbackListener))
            .thenReturn(firstClient);
        manager.activate(9L, "client-id", "client-secret", false);
        doThrow(new IllegalStateException("revoked"))
            .when(clientFactory).validateCredentials("client-id", "client-secret");

        manager.healthCheck();

        verify(firstClient).stop();
        assertFalse(manager.isConnected(9L));
    }
}
