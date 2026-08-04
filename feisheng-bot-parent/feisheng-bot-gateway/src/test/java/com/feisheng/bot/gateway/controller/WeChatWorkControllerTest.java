package com.feisheng.bot.gateway.controller;

import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.service.WeChatImageReplyDispatcher;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import com.feisheng.bot.gateway.util.WeChatWorkCryptoUtil;
import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WeChatWorkControllerTest {
    private static final String AES_KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private static final String TOKEN = "callback-token";
    private static final String CORP_ID = "ww-test-corp";

    @Test
    void verifiesCallbackOnMessageUrl() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        WeChatWorkController controller = controller(channelService);
        WeChatWorkCryptoUtil crypto = crypto();
        String timestamp = "1720588800";
        String nonce = "verify-nonce";
        Document envelope = parseXml(crypto.encryptReply("verified", timestamp, nonce));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/gateway/channel/wechat/message")
                .param("msg_signature", value(envelope, "MsgSignature"))
                .param("timestamp", timestamp)
                .param("nonce", nonce)
                .param("echostr", value(envelope, "Encrypt")))
            .andExpect(status().isOk())
            .andExpect(content().string("verified"));
    }

    @Test
    void decryptsMessageAndReturnsEncryptedReply() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        when(channelService.processMessage(any())).thenReturn(Map.of("reply", "您好，我是智能客服"));
        WeChatWorkController controller = controller(channelService);
        WeChatWorkCryptoUtil crypto = crypto();
        String timestamp = "1720588800";
        String nonce = "message-nonce";
        String incoming = "<xml>"
            + "<ToUserName><![CDATA[" + CORP_ID + "]]></ToUserName>"
            + "<FromUserName><![CDATA[user-1]]></FromUserName>"
            + "<CreateTime>1720588800</CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[你好]]></Content>"
            + "<MsgId>msg-1</MsgId>"
            + "</xml>";
        String requestXml = crypto.encryptReply(incoming, timestamp, nonce);
        Document requestEnvelope = parseXml(requestXml);

        ResponseEntity<String> response = controller.receiveMessage(
            requestXml, value(requestEnvelope, "MsgSignature"), timestamp, nonce);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Document responseEnvelope = parseXml(response.getBody());
        String plainReply = crypto.decryptMsg(
            value(responseEnvelope, "Encrypt"), value(responseEnvelope, "MsgSignature"),
            value(responseEnvelope, "TimeStamp"), value(responseEnvelope, "Nonce"));
        Document reply = parseXml(plainReply);
        assertEquals("user-1", value(reply, "ToUserName"));
        assertEquals(CORP_ID, value(reply, "FromUserName"));
        assertEquals("您好，我是智能客服", value(reply, "Content"));

        ArgumentCaptor<ChannelMessageDTO> message = ArgumentCaptor.forClass(ChannelMessageDTO.class);
        verify(channelService).processMessage(message.capture());
        assertEquals("wechat", message.getValue().getChannelType());
        assertEquals("user-1", message.getValue().getChannelUserId());
        assertEquals("msg-1", message.getValue().getMsgId());
        assertEquals("你好", message.getValue().getContent());
    }

    @Test
    void rejectsInvalidCallbackSignature() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        WeChatWorkController controller = controller(channelService);
        String timestamp = "1720588800";
        String nonce = "invalid-nonce";
        String requestXml = crypto().encryptReply("<xml/>", timestamp, nonce);

        ResponseEntity<String> response = controller.receiveMessage(
            requestXml, "invalid", timestamp, nonce);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void activelySendsKnowledgeImageAlongsideTextReply() throws Exception {
        ChannelServiceImpl channelService = mock(ChannelServiceImpl.class);
        WeChatImageReplyDispatcher imageReplyDispatcher =
            mock(WeChatImageReplyDispatcher.class);
        Map<String, Object> result = Map.of(
            "reply", "这是产品图。",
            "attachments", java.util.List.of(
                new KnowledgeImageService.ImageAttachment(
                    "image", 42L, "产品图", "/api/public/knowledge-images/42")));
        when(channelService.processMessage(any())).thenReturn(result);
        WeChatWorkController controller = new WeChatWorkController(
            channelService, imageReplyDispatcher, CORP_ID, TOKEN, AES_KEY);
        WeChatWorkCryptoUtil crypto = crypto();
        String timestamp = "1720588800";
        String nonce = "image-message-nonce";
        String incoming = "<xml>"
            + "<ToUserName><![CDATA[" + CORP_ID + "]]></ToUserName>"
            + "<FromUserName><![CDATA[user-1]]></FromUserName>"
            + "<CreateTime>1720588800</CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[发一下产品图]]></Content>"
            + "<MsgId>msg-image-1</MsgId>"
            + "</xml>";
        String requestXml = crypto.encryptReply(incoming, timestamp, nonce);
        Document requestEnvelope = parseXml(requestXml);

        ResponseEntity<String> response = controller.receiveMessage(
            requestXml, value(requestEnvelope, "MsgSignature"), timestamp, nonce);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(imageReplyDispatcher).dispatch(result, "user-1");
    }

    @Test
    void returnsServiceUnavailableWhenCallbackCredentialsAreMissing() {
        WeChatWorkController controller = new WeChatWorkController(
            mock(ChannelServiceImpl.class), mock(WeChatImageReplyDispatcher.class),
            "", "", "");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            controller.verifyUrl("signature", "timestamp", "nonce", "echo").getStatusCode());
    }

    private static WeChatWorkController controller(ChannelServiceImpl channelService) {
        return new WeChatWorkController(channelService,
            mock(WeChatImageReplyDispatcher.class), CORP_ID, TOKEN, AES_KEY);
    }

    private static WeChatWorkCryptoUtil crypto() {
        return new WeChatWorkCryptoUtil(AES_KEY, TOKEN, CORP_ID);
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String value(Document document, String tag) {
        return document.getElementsByTagName(tag).item(0).getTextContent();
    }
}
