package com.feisheng.bot.gateway.controller;

import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import com.feisheng.bot.gateway.util.DingTalkCryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/gateway/channel/dingtalk")
public class DingTalkController {
    private static final Logger log = LoggerFactory.getLogger(DingTalkController.class);
    private final ChannelServiceImpl channelService;
    private final String appSecret;

    public DingTalkController(ChannelServiceImpl channelService,
                              @Value("${dingtalk.app-secret:${DINGTALK_APP_SECRET:}}") String appSecret) {
        this.channelService = channelService;
        this.appSecret = appSecret;
    }

    @PostMapping("/message")
    public ResponseEntity<Object> receiveMessage(
            @RequestBody Map<String, Object> body,
            @RequestHeader("timestamp") String timestamp,
            @RequestHeader("sign") String sign) {
        try {
            // Verify signature if secret is configured
            if (appSecret != null && !appSecret.isEmpty()) {
                if (!DingTalkCryptoUtil.verifySignature(timestamp, sign, appSecret)) {
                    log.warn("DingTalk signature verification failed");
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                }
            }

            // Parse DingTalk message body
            String conversationId = (String) body.get("conversationId");
            String msgId = (String) body.get("msgId");
            String senderId = (String) body.get("senderStaffId");
            if (senderId == null) senderId = (String) body.get("senderId");
            if (senderId == null) senderId = conversationId;

            // text.content is the typical DingTalk outgoing robot field
            String text = "";
            Object textObj = body.get("text");
            if (textObj instanceof Map) {
                Object contentVal = ((Map<?, ?>) textObj).get("content");
                if (contentVal != null) text = contentVal.toString();
            }
            // Fallback to content.content for compatibility
            if (text.trim().isEmpty()) {
                Object contentObj = body.get("content");
                if (contentObj instanceof Map) {
                    Object contentVal = ((Map<?, ?>) contentObj).get("content");
                    if (contentVal != null) text = contentVal.toString();
                }
            }

            String msgType = (String) body.get("msgtype");
            if (msgType == null) msgType = "text";

            // Empty message fallback
            if (text.trim().isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "msgtype", "text",
                    "text", Map.of("content", "请发送文本内容，我会尽力帮您解答。")
                ));
            }

            ChannelMessageDTO dto = new ChannelMessageDTO();
            dto.setChannelType("dingtalk");
            dto.setChannelUserId(senderId);
            dto.setMsgId(msgId != null ? msgId : "dt-" + (conversationId + senderId + text).hashCode());
            dto.setContent(text);
            dto.setMsgType(msgType);
            dto.setTimestamp(System.currentTimeMillis());

            Map<String, Object> result = channelService.processMessage(dto);
            String reply = (String) result.get("reply");
            if (reply == null || reply.trim().isEmpty()) {
                if (Boolean.TRUE.equals(result.get("duplicate"))) {
                    reply = "消息已处理，无需重复发送。";
                } else {
                    reply = "服务暂时不可用，请稍后再试。";
                }
            }

            return ResponseEntity.ok(Map.of(
                "msgtype", "text",
                "text", Map.of("content", reply)
            ));
        } catch (Exception e) {
            log.error("DingTalk message processing failed", e);
            return ResponseEntity.ok(Map.of(
                "msgtype", "text",
                "text", Map.of("content", "服务暂时不可用，请稍后再试。")
            ));
        }
    }
}
