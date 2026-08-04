package com.feisheng.bot.gateway.client;

import com.feisheng.bot.common.util.RedisUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeChatWorkClientTest {

    @Test
    void uploadsTemporaryMediaBeforeSendingImageMessage() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://qyapi.weixin.qq.com/cgi-bin/media/upload"
                + "?access_token=access-token&type=image"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
            .andRespond(withSuccess("{\"errcode\":0,\"media_id\":\"media-42\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://qyapi.weixin.qq.com/cgi-bin/message/send"
                + "?access_token=access-token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.touser").value("user-1"))
            .andExpect(jsonPath("$.msgtype").value("image"))
            .andExpect(jsonPath("$.agentid").value(100001))
            .andExpect(jsonPath("$.image.media_id").value("media-42"))
            .andRespond(withSuccess("{\"errcode\":0}", MediaType.APPLICATION_JSON));
        WeChatWorkClient client = new WeChatWorkClient(
            redisUtil, "corp", "secret", "100001", "https://qyapi.weixin.qq.com", restTemplate);

        assertTrue(client.sendImage("corp", "secret", 100001L, "user-1",
            new byte[] {1, 2, 3}, "product.png", "image/png"));
        server.verify();
    }
}
