package com.feisheng.bot.gateway.controller;

import com.feisheng.bot.gateway.config.WeChatWorkConfigProvider;
import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import com.feisheng.bot.gateway.service.WeChatImageReplyDispatcher;
import com.feisheng.bot.gateway.util.WeChatWorkCryptoUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/gateway/channel/wechat")
public class WeChatWorkController {
    private static final Logger log = LoggerFactory.getLogger(WeChatWorkController.class);

    private final ChannelServiceImpl channelService;
    private final WeChatImageReplyDispatcher imageReplyDispatcher;
    private final WeChatWorkCryptoUtil environmentCrypto;
    private final ObjectProvider<WeChatWorkConfigProvider> configProvider;

    public WeChatWorkController(ChannelServiceImpl channelService,
                                WeChatImageReplyDispatcher imageReplyDispatcher,
                                @Value("${wecom.corp-id:}") String corpId,
                                @Value("${wecom.callback-token:}") String token,
                                @Value("${wecom.callback-aes-key:}") String aesKey) {
        this(channelService, imageReplyDispatcher, null, corpId, token, aesKey);
    }

    @org.springframework.beans.factory.annotation.Autowired
    WeChatWorkController(ChannelServiceImpl channelService,
                         WeChatImageReplyDispatcher imageReplyDispatcher,
                         ObjectProvider<WeChatWorkConfigProvider> configProvider,
                         @Value("${wecom.corp-id:}") String corpId,
                         @Value("${wecom.callback-token:}") String token,
                         @Value("${wecom.callback-aes-key:}") String aesKey) {
        this.channelService = channelService;
        this.imageReplyDispatcher = imageReplyDispatcher;
        this.environmentCrypto = isBlank(corpId) || isBlank(token) || isBlank(aesKey)
            ? null : new WeChatWorkCryptoUtil(aesKey, token, corpId);
        this.configProvider = configProvider;
    }

    @GetMapping(value = {"/message", "/verify"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verifyUrl(
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestParam String echostr) {
        WeChatWorkCryptoUtil crypto = callbackCrypto();
        if (crypto == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        if (!crypto.verifySignature(msgSignature, timestamp, nonce, echostr)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("error");
        }
        try {
            return ResponseEntity.ok(crypto.decryptEchoStr(echostr));
        } catch (Exception e) {
            log.error("WeCom URL verification failed", e);
            return ResponseEntity.badRequest().body("error");
        }
    }

    @PostMapping(value = "/message", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
        produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> receiveMessage(
            @RequestBody String requestXml,
            @RequestParam("msg_signature") String msgSignature,
            @RequestParam String timestamp,
            @RequestParam String nonce) {
        WeChatWorkCryptoUtil crypto = callbackCrypto();
        if (crypto == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        try {
            String encrypted = xmlValue(parseXml(requestXml), "Encrypt");
            if (encrypted.isBlank()) return ResponseEntity.badRequest().build();
            String decryptedXml = crypto.decryptMsg(encrypted, msgSignature, timestamp, nonce);
            Document message = parseXml(decryptedXml);
            String content = xmlValue(message, "Content");
            String fromUser = xmlValue(message, "FromUserName");
            String toUser = xmlValue(message, "ToUserName");
            String msgId = xmlValue(message, "MsgId");
            String msgType = xmlValue(message, "MsgType");
            if (content.isBlank() || fromUser.isBlank() || msgId.isBlank()) {
                return ResponseEntity.badRequest().build();
            }

            ChannelMessageDTO dto = new ChannelMessageDTO();
            dto.setChannelType("wechat");
            dto.setChannelUserId(fromUser);
            dto.setMsgId(msgId);
            dto.setContent(content);
            dto.setMsgType(msgType);
            dto.setTimestamp(System.currentTimeMillis());
            Map<String, Object> result = channelService.processMessage(dto);
            imageReplyDispatcher.dispatch(result, fromUser);
            String reply = replyFrom(result);
            String plainReply = buildTextReply(fromUser, toUser, reply);
            String responseTimestamp = String.valueOf(Instant.now().getEpochSecond());
            return ResponseEntity.ok(crypto.encryptReply(plainReply, responseTimestamp, nonce));
        } catch (SecurityException e) {
            log.warn("WeCom callback rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("WeCom message processing failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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

    private static String xmlValue(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private static String buildTextReply(String toUser, String fromUser, String content) {
        return "<xml>"
            + "<ToUserName><![CDATA[" + cdata(toUser) + "]]></ToUserName>"
            + "<FromUserName><![CDATA[" + cdata(fromUser) + "]]></FromUserName>"
            + "<CreateTime>" + Instant.now().getEpochSecond() + "</CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[" + cdata(content) + "]]></Content>"
            + "</xml>";
    }

    private static String replyFrom(Map<String, Object> result) {
        Object reply = result.get("reply");
        if (reply != null && !reply.toString().isBlank()) return reply.toString();
        return Boolean.TRUE.equals(result.get("duplicate"))
            ? "消息已处理，无需重复发送。" : "服务暂时不可用，请稍后再试。";
    }

    private static String cdata(String value) {
        return value == null ? "" : value.replace("]]>", "]]]]><![CDATA[>");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private WeChatWorkCryptoUtil callbackCrypto() {
        if (configProvider != null) {
            WeChatWorkConfigProvider provider = configProvider.getIfAvailable();
            if (provider != null) {
                WeChatWorkConfigProvider.Config active = provider.activeConfig().orElse(null);
                if (active != null) {
                    if (!active.hasCallbackCredentials()) return null;
                    return new WeChatWorkCryptoUtil(
                        active.callbackAesKey(), active.callbackToken(), active.corpId());
                }
            }
        }
        return environmentCrypto;
    }
}
