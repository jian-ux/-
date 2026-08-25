package com.feisheng.bot.gateway.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.dto.DingTalkMediaRequest;
import com.feisheng.bot.gateway.service.DingTalkMediaProcessingException;
import com.feisheng.bot.gateway.service.DingTalkMediaProcessor;
import com.feisheng.bot.gateway.service.DingTalkImageReplyDispatcher.ReplyTarget;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import com.feisheng.bot.gateway.stream.DingTalkStreamCallbackListener;
import com.feisheng.bot.gateway.util.DingTalkCryptoUtil;
import com.feisheng.bot.gateway.util.DingTalkReplyTargetMetadata;
import com.feisheng.bot.gateway.util.ReplyAttachmentUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/gateway/channel/dingtalk")
public class DingTalkController {
    private static final Logger log = LoggerFactory.getLogger(DingTalkController.class);
    private static final long MAX_TIMESTAMP_SKEW_MS = 60 * 60 * 1000L;

    private final ChannelServiceImpl channelService;
    private final ObjectMapper objectMapper;
    private final Supplier<DingTalkMediaProcessor> mediaProcessorSupplier;
    private final DingTalkStreamCallbackListener streamCallbackListener;
    private final String appSecret;
    private final String token;
    private final String encodingAesKey;

    @Autowired
    public DingTalkController(ChannelServiceImpl channelService, ObjectMapper objectMapper,
                              ObjectProvider<DingTalkMediaProcessor> mediaProcessor,
                              DingTalkStreamCallbackListener streamCallbackListener,
                              @Value("${dingtalk.app-secret:}") String appSecret,
                              @Value("${dingtalk.token:}") String token,
                              @Value("${dingtalk.encoding-aes-key:}") String encodingAesKey) {
        this(channelService, objectMapper,
            (Supplier<DingTalkMediaProcessor>) mediaProcessor::getIfAvailable,
            streamCallbackListener, appSecret, token, encodingAesKey);
    }

    public DingTalkController(ChannelServiceImpl channelService, ObjectMapper objectMapper,
                              String appSecret, String token, String encodingAesKey) {
        this(channelService, objectMapper, () -> null, null,
            appSecret, token, encodingAesKey);
    }

    DingTalkController(ChannelServiceImpl channelService, ObjectMapper objectMapper,
                       DingTalkMediaProcessor mediaProcessor,
                       DingTalkStreamCallbackListener streamCallbackListener,
                       String appSecret, String token, String encodingAesKey) {
        this(channelService, objectMapper, () -> mediaProcessor,
            streamCallbackListener, appSecret, token, encodingAesKey);
    }

    private DingTalkController(ChannelServiceImpl channelService, ObjectMapper objectMapper,
                               Supplier<DingTalkMediaProcessor> mediaProcessorSupplier,
                               DingTalkStreamCallbackListener streamCallbackListener,
                               String appSecret, String token, String encodingAesKey) {
        this.channelService = channelService;
        this.objectMapper = objectMapper;
        this.mediaProcessorSupplier = mediaProcessorSupplier;
        this.streamCallbackListener = streamCallbackListener;
        this.appSecret = appSecret;
        this.token = token;
        this.encodingAesKey = encodingAesKey;
    }

    @GetMapping("/message")
    public Map<String, Object> verifyUrl() {
        return textResponse("ok");
    }

    @PostMapping(value = "/message", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receiveMessage(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            Object encrypted = body.get("encrypt");
            if (encrypted instanceof String value && !value.isBlank()) {
                return handleEnterpriseRobot(value, request);
            }
            return handleOutgoingRobot(body, request);
        } catch (Exception e) {
            log.error("DingTalk request processing failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(textResponse("服务暂时不可用，请稍后再试。"));
        }
    }

    private ResponseEntity<Map<String, Object>> handleOutgoingRobot(
            Map<String, Object> body, HttpServletRequest request) {
        if (appSecret == null || appSecret.isBlank()) {
            log.error("DINGTALK_APP_SECRET is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        String timestamp = request.getHeader("timestamp");
        String sign = request.getHeader("sign");
        if (!isFreshTimestamp(timestamp)
                || !DingTalkCryptoUtil.verifySignature(timestamp, sign, appSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String conversationId = stringValue(body.get("conversationId"));
        String senderId = firstNonBlank(
            stringValue(body.get("senderStaffId")), stringValue(body.get("senderId")), conversationId);
        String msgType = normalizeType(stringValue(body.get("msgtype")));
        String text = nestedContent(body.get("text"));
        if (text.isBlank()) text = nestedContent(body.get("content"));
        DingTalkMediaRequest media = mediaFrom(body, msgType);
        if (media != null) text = "";
        if (text.isBlank() && media == null) {
            return ResponseEntity.ok(textResponse("请发送文本内容，我会尽力帮您解答。"));
        }

        String msgId = stringValue(body.get("msgId"));
        if (msgId.isBlank()) {
            String identity = media == null ? text
                : firstNonBlank(media.downloadCode(), media.recognition(), msgType);
            msgId = "dt-" + (conversationId + senderId + identity).hashCode();
        }
        ChannelMessageDTO dto = channelMessage(
            "dingtalk", senderId, stringValue(body.get("senderNick")),
            msgId, text, msgType);
        String sessionWebhook = stringValue(body.get("sessionWebhook")).trim();
        ReplyTarget replyTarget = new ReplyTarget(
            firstNonBlank(stringValue(body.get("senderStaffId")),
                stringValue(body.get("senderId"))),
            conversationId, stringValue(body.get("conversationType")),
            stringValue(body.get("robotCode")));
        dto.setMessageMetadata(DingTalkReplyTargetMetadata.merge(
            objectMapper, dto.getMessageMetadata(), replyTarget));
        if (media != null) {
            DingTalkMediaRequest identified = new DingTalkMediaRequest(
                msgId, media.msgType(), media.downloadCode(), media.recognition(),
                media.fileName(), media.robotCode(), media.caption());
            if (!sessionWebhook.isBlank() && streamCallbackListener != null) {
                streamCallbackListener.dispatchMedia(
                    dto, identified, sessionWebhook, replyTarget);
                return ResponseEntity.ok(new LinkedHashMap<>());
            }
            try {
                return ResponseEntity.ok(replyResponse(processMedia(dto, identified)));
            } catch (DingTalkMediaProcessingException e) {
                return ResponseEntity.ok(textResponse(e.getUserMessage()));
            }
        }
        if (!sessionWebhook.isBlank() && streamCallbackListener != null) {
            streamCallbackListener.dispatchText(dto, sessionWebhook, replyTarget);
            return ResponseEntity.ok(new LinkedHashMap<>());
        }
        return ResponseEntity.ok(replyResponse(channelService.processMessage(dto)));
    }

    private ResponseEntity<Map<String, Object>> handleEnterpriseRobot(
            String encrypt, HttpServletRequest request) throws Exception {
        if (token == null || token.isBlank() || encodingAesKey == null || encodingAesKey.isBlank()) {
            log.error("DINGTALK_TOKEN or DINGTALK_ENCODING_AES_KEY is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        String timestamp = request.getParameter("timestamp");
        String nonce = request.getParameter("nonce");
        String signature = firstNonBlank(
            request.getParameter("msg_signature"), request.getParameter("signature"));
        if (!isFreshTimestamp(timestamp)
                || !DingTalkCryptoUtil.verifyEnterpriseSignature(token, timestamp, nonce, encrypt, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        DingTalkCryptoUtil.DecryptedPayload payload =
            DingTalkCryptoUtil.decryptPayload(encrypt, encodingAesKey);
        Map<String, Object> message = objectMapper.readValue(
            payload.message(), new TypeReference<Map<String, Object>>() {});
        String msgType = normalizeType(stringValue(message.get("msgtype")));
        String content = nestedContent(message.get("text"));
        if (content.isBlank()) content = nestedContent(message.get("content"));
        DingTalkMediaRequest media = mediaFrom(message, msgType);
        if (media != null) content = "";
        String senderId = firstNonBlank(
            stringValue(message.get("senderStaffId")), stringValue(message.get("senderId")),
            stringValue(message.get("userid")));
        String msgId = firstNonBlank(
            stringValue(message.get("msgId")), stringValue(message.get("msgid")));
        if (senderId.isBlank() || msgId.isBlank()
                || (content.isBlank() && media == null)) {
            return ResponseEntity.badRequest().build();
        }

        ChannelMessageDTO dto = channelMessage(
            "dingtalk", senderId, stringValue(message.get("senderNick")),
            msgId, content, msgType);
        ReplyTarget replyTarget = new ReplyTarget(
            firstNonBlank(stringValue(message.get("senderStaffId")),
                stringValue(message.get("senderId")), senderId),
            stringValue(message.get("conversationId")),
            stringValue(message.get("conversationType")),
            stringValue(message.get("robotCode")));
        dto.setMessageMetadata(DingTalkReplyTargetMetadata.merge(
            objectMapper, dto.getMessageMetadata(), replyTarget));
        String reply;
        try {
            Map<String, Object> result = media == null
                ? channelService.processMessage(dto)
                : processMedia(dto, new DingTalkMediaRequest(
                    msgId, media.msgType(), media.downloadCode(), media.recognition(),
                    media.fileName(), media.robotCode(), media.caption()));
            reply = replyFrom(result);
        } catch (DingTalkMediaProcessingException e) {
            reply = e.getUserMessage();
        }
        String responseTimestamp = String.valueOf(System.currentTimeMillis());
        String responseNonce = nonce == null ? "" : nonce;
        String encryptedReply = DingTalkCryptoUtil.encrypt(
            reply, payload.receiveId(), encodingAesKey);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("msg_signature", DingTalkCryptoUtil.computeEnterpriseSignature(
            token, responseTimestamp, responseNonce, encryptedReply));
        response.put("timeStamp", responseTimestamp);
        response.put("nonce", responseNonce);
        response.put("encrypt", encryptedReply);
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> processMedia(ChannelMessageDTO dto,
                                             DingTalkMediaRequest media) {
        DingTalkMediaProcessor processor = mediaProcessorSupplier.get();
        if (processor == null) {
            throw new DingTalkMediaProcessingException(
                "当前未启用图片或语音识别，请改用文字发送。");
        }
        return channelService.processMessage(dto, () -> {
            DingTalkMediaProcessor.MediaResult result = processor.process(media);
            // Keep compatibility with processors that only implement normalize().
            if (result == null) result = new DingTalkMediaProcessor.MediaResult(
                processor.normalize(media), "text", null);
            dto.setMessageContentType(result.contentType());
            dto.setMessageMetadata(DingTalkReplyTargetMetadata.merge(
                objectMapper, result.metadata(),
                DingTalkReplyTargetMetadata.readTarget(objectMapper, dto.getMessageMetadata())));
            return result.content();
        });
    }

    private DingTalkMediaRequest mediaFrom(Map<String, Object> message, String msgType) {
        Map<String, Object> content = contentMap(message.get("content"));
        if (content.isEmpty()) content = contentMap(message.get("text"));
        if ("richtext".equals(msgType)) {
            return pictureFromRichText(message, content);
        }
        if (!"picture".equals(msgType) && !"image".equals(msgType)
                && !"audio".equals(msgType) && !"voice".equals(msgType)) {
            return null;
        }
        String downloadCode = firstNonBlank(
            stringValue(content.get("downloadCode")),
            stringValue(content.get("pictureDownloadCode")),
            stringValue(message.get("downloadCode")),
            stringValue(message.get("pictureDownloadCode")));
        String recognition = firstNonBlank(
            stringValue(content.get("recognition")), stringValue(message.get("recognition")));
        String fileName = firstNonBlank(
            stringValue(content.get("fileName")), stringValue(message.get("fileName")));
        String robotCode = firstNonBlank(
            stringValue(message.get("robotCode")), stringValue(content.get("robotCode")));
        return new DingTalkMediaRequest(stringValue(message.get("msgId")), msgType,
            downloadCode, recognition, fileName, robotCode,
            mediaCaption(message, content, msgType));
    }

    private DingTalkMediaRequest pictureFromRichText(
            Map<String, Object> message, Map<String, Object> content) {
        Object richText = content.get("richText");
        if (!(richText instanceof Iterable<?> items)) return null;
        for (Object value : items) {
            Map<String, Object> item = contentMap(value);
            String pictureCode = stringValue(item.get("pictureDownloadCode")).trim();
            String downloadCode = firstNonBlank(
                stringValue(item.get("downloadCode")).trim(), pictureCode);
            if (("picture".equals(normalizeType(stringValue(item.get("type"))))
                    || !pictureCode.isBlank()) && !downloadCode.isBlank()) {
                String robotCode = firstNonBlank(
                    stringValue(message.get("robotCode")),
                    stringValue(content.get("robotCode")),
                    stringValue(item.get("robotCode")));
                return new DingTalkMediaRequest(stringValue(message.get("msgId")),
                    "picture", downloadCode, "",
                    stringValue(item.get("fileName")).trim(), robotCode,
                    richTextCaption(richText));
            }
        }
        return null;
    }

    private Map<String, Object> contentMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(stringValue(key), item));
            return result;
        }
        if (value instanceof String text && text.trim().startsWith("{")) {
            try {
                return objectMapper.readValue(text, new TypeReference<>() {});
            } catch (Exception ignored) {
            }
        }
        return Map.of();
    }

    private String mediaCaption(Map<String, Object> message, Map<String, Object> content,
                                String msgType) {
        if ("richtext".equals(msgType)) {
            return richTextCaption(content.get("richText"));
        }
        String caption = nestedContent(message.get("text"));
        if (caption.isBlank()) caption = stringValue(content.get("content")).trim();
        return caption;
    }

    private String richTextCaption(Object richText) {
        if (!(richText instanceof Iterable<?> items)) return "";
        StringBuilder caption = new StringBuilder();
        for (Object value : items) {
            Map<String, Object> item = contentMap(value);
            String type = normalizeType(stringValue(item.get("type")));
            if ("picture".equals(type) || "image".equals(type)
                    || "at".equals(type) || "mention".equals(type)) {
                continue;
            }
            String text = firstNonBlank(
                stringValue(item.get("text")), stringValue(item.get("content"))).trim();
            if (text.isBlank()) continue;
            if (caption.length() > 0) caption.append('\n');
            caption.append(text);
        }
        return caption.toString().trim();
    }

    private ChannelMessageDTO channelMessage(String channel, String userId, String senderName,
                                             String msgId, String content, String msgType) {
        ChannelMessageDTO dto = new ChannelMessageDTO();
        dto.setChannelType(channel);
        dto.setChannelUserId(userId);
        dto.setSenderName(senderName);
        dto.setMsgId(msgId);
        dto.setContent(content);
        dto.setMsgType(msgType == null || msgType.isBlank() ? "text" : msgType);
        dto.setTimestamp(System.currentTimeMillis());
        return dto;
    }

    private boolean isFreshTimestamp(String value) {
        try {
            long timestamp = Long.parseLong(value);
            if (timestamp < 10_000_000_000L) timestamp *= 1000;
            return Math.abs(System.currentTimeMillis() - timestamp) <= MAX_TIMESTAMP_SKEW_MS;
        } catch (Exception e) {
            return false;
        }
    }

    private static String replyFrom(Map<String, Object> result) {
        if (result != null && (Boolean.TRUE.equals(result.get("duplicate"))
                || Boolean.TRUE.equals(result.get("suppressReply")))) return "";
        String reply = stringValue(result.get("reply"));
        if (!reply.isBlank()) return reply;
        return "服务暂时不可用，请稍后再试。";
    }

    private static Map<String, Object> textResponse(String content) {
        return Map.of("msgtype", "text", "text", Map.of("content", content));
    }

    private static Map<String, Object> replyResponse(Map<String, Object> result) {
        String reply = replyFrom(result);
        if (reply.isBlank()) return Map.of();
        String richReply = ReplyAttachmentUtils.richReply(result);
        List<ReplyAttachmentUtils.ImageAttachment> images =
            ReplyAttachmentUtils.publicImages(result);
        if (images.isEmpty() && richReply.isBlank()) return textResponse(reply);
        return Map.of(
            "msgtype", "markdown",
            "markdown", Map.of(
                "title", "智能客服回复",
                "text", ReplyAttachmentUtils.markdown(
                    richReply.isBlank() ? reply : richReply, images)));
    }

    private static String nestedContent(Object value) {
        if (value instanceof Map<?, ?> map) return stringValue(map.get("content")).trim();
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
}
