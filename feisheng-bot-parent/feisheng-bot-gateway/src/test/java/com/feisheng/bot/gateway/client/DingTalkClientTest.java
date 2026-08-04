package com.feisheng.bot.gateway.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.common.util.RedisUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

class DingTalkClientTest {
    private static final String SEND_URL =
        "https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend";
    private static final String MESSAGE_FILE_URL =
        "https://api.dingtalk.com/v1.0/robot/messageFiles/download";
    private static final String DOWNLOAD_URL =
        "https://download.dingtalk.com/temporary/media-file";

    @Test
    void sendsTextUsingDingTalkBatchMessageSchema() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo(SEND_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-acs-dingtalk-access-token", "access-token"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.robotCode").value("robot-code"))
            .andExpect(jsonPath("$.userIds[0]").value("staff-1"))
            .andExpect(jsonPath("$.msgKey").value("sampleText"))
            .andExpect(jsonPath("$.msgParam").value("{\"content\":\"你好\"}"))
            .andRespond(withSuccess("{\"processQueryKey\":\"query-key\"}",
                MediaType.APPLICATION_JSON));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);

        assertTrue(client.sendRobotMessage(
            "app-key", "app-secret", "robot-code", "staff-1", "你好"));
        server.verify();
    }

    @Test
    void exposesDingTalkErrorCodeAndMessage() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo(SEND_URL))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"InvalidParameter\","
                    + "\"message\":\"userIds is invalid\"}"));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> client.sendRobotMessage(
                "app-key", "app-secret", "robot-code", "staff-1", "你好"));

        assertTrue(error.getMessage().contains("HTTP 400"));
        assertTrue(error.getMessage().contains("InvalidParameter"));
        assertTrue(error.getMessage().contains("userIds is invalid"));
        server.verify();
    }

    @Test
    void exchangesDownloadCodeAndDownloadsBoundedMedia() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        byte[] content = "image-content".getBytes(StandardCharsets.UTF_8);

        server.expect(requestTo(MESSAGE_FILE_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-acs-dingtalk-access-token", "access-token"))
            .andExpect(jsonPath("$.downloadCode").value("download-code"))
            .andExpect(jsonPath("$.robotCode").value("robot-code"))
            .andRespond(withSuccess("{\"downloadUrl\":\"" + DOWNLOAD_URL + "\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(DOWNLOAD_URL))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(content, MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=customer-photo.jpg"));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);
        DingTalkClient.DownloadedMedia result = client.downloadRobotMessageFile(
            "app-key", "app-secret", "robot-code", "download-code", 1024);

        assertArrayEquals(content, result.content());
        assertEquals("image/jpeg", result.contentType());
        assertEquals("customer-photo.jpg", result.fileName());
        server.verify();
    }

    @Test
    void upgradesTrustedDingTalkHttpMediaUrlToHttps() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        String httpUrl = "http://wukong-file.oss-cn-hangzhou.aliyuncs.com/media.file?Expires=1";
        String httpsUrl = "https://wukong-file.oss-cn-hangzhou.aliyuncs.com/media.file?Expires=1";

        server.expect(requestTo(MESSAGE_FILE_URL))
            .andRespond(withSuccess("{\"downloadUrl\":\"" + httpUrl + "\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(httpsUrl))
            .andRespond(withSuccess("image-content", MediaType.IMAGE_JPEG));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);
        DingTalkClient.DownloadedMedia result = client.downloadRobotMessageFile(
            "app-key", "app-secret", "robot-code", "download-code", 1024);

        assertEquals("image-content", new String(result.content(), StandardCharsets.UTF_8));
        server.verify();
    }

    @Test
    void rejectsUntrustedHttpMediaUrl() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo(MESSAGE_FILE_URL))
            .andRespond(withSuccess(
                "{\"downloadUrl\":\"http://127.0.0.1/internal\"}",
                MediaType.APPLICATION_JSON));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> client.downloadRobotMessageFile(
                "app-key", "app-secret", "robot-code", "download-code", 1024));

        assertTrue(error.getMessage().contains("未返回可信的媒体下载地址"));
        server.verify();
    }

    @Test
    void retriesTransientTimeoutWhenExchangingDownloadCode() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        byte[] content = "image-content".getBytes(StandardCharsets.UTF_8);

        server.expect(requestTo(MESSAGE_FILE_URL))
            .andRespond(withException(new SocketTimeoutException("connect timed out")));
        server.expect(requestTo(MESSAGE_FILE_URL))
            .andRespond(withException(new SocketTimeoutException("connect timed out")));
        server.expect(requestTo(MESSAGE_FILE_URL))
            .andRespond(withSuccess("{\"downloadUrl\":\"" + DOWNLOAD_URL + "\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(DOWNLOAD_URL))
            .andRespond(withSuccess(content, MediaType.IMAGE_JPEG));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);
        DingTalkClient.DownloadedMedia result = client.downloadRobotMessageFile(
            "app-key", "app-secret", "robot-code", "download-code", 1024);

        assertArrayEquals(content, result.content());
        server.verify();
    }

    @Test
    void rejectsMediaLargerThanConfiguredLimit() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);

        server.expect(requestTo(MESSAGE_FILE_URL))
            .andRespond(withSuccess("{\"downloadUrl\":\"" + DOWNLOAD_URL + "\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(DOWNLOAD_URL))
            .andRespond(withSuccess(new byte[] {1, 2, 3, 4},
                MediaType.APPLICATION_OCTET_STREAM));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> client.downloadRobotMessageFile(
                "app-key", "app-secret", "robot-code", "download-code", 3));

        assertTrue(error.getMessage().contains("超过大小限制"));
        server.verify();
    }
}
