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

import static org.hamcrest.Matchers.containsString;
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
    private static final String GROUP_SEND_URL =
        "https://api.dingtalk.com/v1.0/robot/groupMessages/send";
    private static final String MEDIA_UPLOAD_URL =
        "https://oapi.dingtalk.com/media/upload?access_token=access-token&type=image";
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
    void sendsTextToDingTalkGroupUsingGroupMessageSchema() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo(GROUP_SEND_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-acs-dingtalk-access-token", "access-token"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.robotCode").value("robot-code"))
            .andExpect(jsonPath("$.openConversationId").value("cid-group"))
            .andExpect(jsonPath("$.msgKey").value("sampleText"))
            .andExpect(jsonPath("$.msgParam").value("{\"content\":\"你好\"}"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);

        assertTrue(client.sendRobotMessageToGroup(
            "app-key", "app-secret", "robot-code", "cid-group", "你好"));
        server.verify();
    }

    @Test
    void sendsMarkdownUsingDingTalkBatchMessageSchema() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo(SEND_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.robotCode").value("robot-code"))
            .andExpect(jsonPath("$.userIds[0]").value("staff-1"))
            .andExpect(jsonPath("$.msgKey").value("sampleMarkdown"))
            .andExpect(jsonPath("$.msgParam",
                containsString("\"title\":\"客服回复\"")))
            .andExpect(jsonPath("$.msgParam",
                containsString("\"text\":\"请参考下图\\n\\n![操作图]"
                    + "(https://bot.example.com/image.png)\"")))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);

        assertTrue(client.sendRobotMarkdown(
            "app-key", "app-secret", "robot-code", "staff-1", "客服回复",
            "请参考下图\n\n![操作图](https://bot.example.com/image.png)"));
        server.verify();
    }

    @Test
    void uploadsImageAndReturnsMediaId() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo(MEDIA_UPLOAD_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
            .andExpect(content().string(containsString("name=\"media\"")))
            .andExpect(content().string(containsString("filename=\"product.png\"")))
            .andRespond(withSuccess(
                "{\"errcode\":0,\"errmsg\":\"ok\",\"media_id\":\"@lAL-image\"}",
                MediaType.APPLICATION_JSON));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);

        assertEquals("@lAL-image", client.uploadImage(
            "app-key", "app-secret", new byte[] {1, 2, 3},
            "product.png", "image/png"));
        server.verify();
    }

    @Test
    void sendsIndependentImageToUser() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo(SEND_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-acs-dingtalk-access-token", "access-token"))
            .andExpect(jsonPath("$.robotCode").value("robot-code"))
            .andExpect(jsonPath("$.userIds[0]").value("staff-1"))
            .andExpect(jsonPath("$.msgKey").value("sampleImageMsg"))
            .andExpect(jsonPath("$.msgParam").value("{\"photoURL\":\"@lAL-image\"}"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);

        assertTrue(client.sendImageToUser(
            "app-key", "app-secret", "robot-code", "staff-1", "@lAL-image"));
        server.verify();
    }

    @Test
    void sendsIndependentImageToGroup() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get(anyString())).thenReturn("access-token");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo(GROUP_SEND_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-acs-dingtalk-access-token", "access-token"))
            .andExpect(jsonPath("$.robotCode").value("robot-code"))
            .andExpect(jsonPath("$.openConversationId").value("cid-group"))
            .andExpect(jsonPath("$.msgKey").value("sampleImageMsg"))
            .andExpect(jsonPath("$.msgParam").value("{\"photoURL\":\"@lAL-image\"}"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        DingTalkClient client = new DingTalkClient(
            redisUtil, new ObjectMapper(), restTemplate);

        assertTrue(client.sendImageToGroup(
            "app-key", "app-secret", "robot-code", "cid-group", "@lAL-image"));
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
