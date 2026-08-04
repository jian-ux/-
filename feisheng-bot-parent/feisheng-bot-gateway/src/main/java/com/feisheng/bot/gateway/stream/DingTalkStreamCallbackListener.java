package com.feisheng.bot.gateway.stream;

import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.feisheng.bot.gateway.dto.ChannelMessageDTO;
import com.feisheng.bot.gateway.dto.DingTalkMediaRequest;
import com.feisheng.bot.gateway.service.DingTalkMediaProcessingException;
import com.feisheng.bot.gateway.service.DingTalkMediaProcessor;
import com.feisheng.bot.gateway.service.impl.ChannelServiceImpl;
import com.feisheng.bot.gateway.util.ReplyAttachmentUtils;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class DingTalkStreamCallbackListener
        implements OpenDingTalkCallbackListener<ChatbotMessage, Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamCallbackListener.class);
    private static final String EMPTY_TEXT_REPLY = "请发送文本内容，我会尽力帮您解答。";
    private static final String DUPLICATE_REPLY = "消息已处理，无需重复发送。";
    private static final String ERROR_REPLY = "服务暂时不可用，请稍后再试。";
    private static final String MEDIA_UNAVAILABLE_REPLY =
        "当前未启用图片或语音识别，请改用文字发送。";
    private static final String MEDIA_BUSY_REPLY =
        "当前图片或语音识别任务较多，请稍后重试。";

    private final ChannelServiceImpl channelService;
    private final DingTalkStreamReplySender replySender;
    private final Supplier<DingTalkMediaProcessor> mediaProcessorSupplier;
    private final Executor mediaExecutor;
    private final ThreadPoolExecutor managedExecutor;

    @Autowired
    public DingTalkStreamCallbackListener(ChannelServiceImpl channelService,
                                          DingTalkStreamReplySender replySender,
                                          ObjectProvider<DingTalkMediaProcessor> mediaProcessor,
                                          @Value("${dingtalk.media.worker-threads:2}") int workerThreads,
                                          @Value("${dingtalk.media.queue-capacity:50}") int queueCapacity) {
        this(channelService, replySender,
            (Supplier<DingTalkMediaProcessor>) mediaProcessor::getIfAvailable,
            createExecutor(workerThreads, queueCapacity));
    }

    DingTalkStreamCallbackListener(ChannelServiceImpl channelService,
                                   DingTalkStreamReplySender replySender) {
        this(channelService, replySender, () -> null, Runnable::run);
    }

    DingTalkStreamCallbackListener(ChannelServiceImpl channelService,
                                   DingTalkStreamReplySender replySender,
                                   DingTalkMediaProcessor mediaProcessor,
                                   Executor mediaExecutor) {
        this(channelService, replySender, () -> mediaProcessor, mediaExecutor);
    }

    private DingTalkStreamCallbackListener(ChannelServiceImpl channelService,
                                           DingTalkStreamReplySender replySender,
                                           Supplier<DingTalkMediaProcessor> mediaProcessorSupplier,
                                           Executor mediaExecutor) {
        this.channelService = channelService;
        this.replySender = replySender;
        this.mediaProcessorSupplier = mediaProcessorSupplier;
        this.mediaExecutor = mediaExecutor;
        this.managedExecutor = mediaExecutor instanceof ThreadPoolExecutor pool ? pool : null;
    }

    @Override
    public Map<String, Object> execute(ChatbotMessage message) {
        if (message == null) return Collections.emptyMap();

        String sessionWebhook = value(message.getSessionWebhook());
        if (sessionWebhook.isBlank()) {
            log.warn("DingTalk Stream message has no sessionWebhook, msgId={}", message.getMsgId());
            return Collections.emptyMap();
        }

        String msgType = normalizeType(message.getMsgtype());
        DingTalkMediaRequest media = mediaFrom(message, msgType);
        // Media normalization owns the complete input when a caption and an
        // attachment arrive together. This keeps the caption and OCR/ASR in
        // one deterministic message for the core dialog pipeline.
        String content = media == null ? contentFrom(message) : "";
        if (content.isBlank() && media == null) {
            replySafely(sessionWebhook, EMPTY_TEXT_REPLY, message.getMsgId());
            return Collections.emptyMap();
        }

        String userId = firstNonBlank(
            message.getSenderStaffId(), message.getSenderId(), message.getConversationId());
        String msgId = value(message.getMsgId());
        if (msgId.isBlank()) {
            msgId = "dt-stream-" + (value(message.getConversationId()) + userId + content).hashCode();
        }

        ChannelMessageDTO dto = new ChannelMessageDTO();
        dto.setChannelType("dingtalk");
        dto.setChannelUserId(userId);
        dto.setSenderName(value(message.getSenderNick()).trim());
        dto.setMsgId(msgId);
        dto.setContent(content);
        dto.setMsgType(firstNonBlank(msgType, "text"));
        dto.setTimestamp(message.getCreateAt() == null ? System.currentTimeMillis() : message.getCreateAt());

        if (media != null) {
            dispatchMedia(dto, media, sessionWebhook);
            return Collections.emptyMap();
        }

        processAndReply(dto, sessionWebhook, null);
        return Collections.emptyMap();
    }

    public boolean dispatchMedia(ChannelMessageDTO dto, DingTalkMediaRequest media,
                                 String sessionWebhook) {
        try {
            mediaExecutor.execute(() -> processAndReply(dto, sessionWebhook,
                () -> normalizeMedia(media)));
            return true;
        } catch (RuntimeException e) {
            log.warn("DingTalk media queue rejected message, msgId={}", dto.getMsgId());
            replySafely(sessionWebhook, MEDIA_BUSY_REPLY, dto.getMsgId());
            return false;
        }
    }

    private String normalizeMedia(DingTalkMediaRequest media) {
        DingTalkMediaProcessor processor = mediaProcessorSupplier.get();
        if (processor == null) {
            throw new DingTalkMediaProcessingException(MEDIA_UNAVAILABLE_REPLY);
        }
        return processor.normalize(media);
    }

    private void processAndReply(ChannelMessageDTO dto, String sessionWebhook,
                                 Supplier<String> contentSupplier) {
        try {
            Map<String, Object> result = contentSupplier == null
                ? channelService.processMessage(dto)
                : channelService.processMessage(dto, contentSupplier);
            sendResult(sessionWebhook, result);
            log.info("DingTalk Stream message processed, msgId={}", dto.getMsgId());
        } catch (DingTalkMediaProcessingException e) {
            log.warn("DingTalk media processing failed, msgId={}: {}",
                dto.getMsgId(), e.getMessage());
            replySafely(sessionWebhook, e.getUserMessage(), dto.getMsgId());
        } catch (Exception e) {
            log.error("DingTalk Stream message processing failed, msgId={}", dto.getMsgId(), e);
            replySafely(sessionWebhook, ERROR_REPLY, dto.getMsgId());
        }
    }

    private void sendResult(String sessionWebhook, Map<String, Object> result) throws Exception {
        String reply = replyFrom(result);
        List<ReplyAttachmentUtils.ImageAttachment> images =
            ReplyAttachmentUtils.publicImages(result);
        if (images.isEmpty()) {
            replySender.replyText(sessionWebhook, reply);
        } else {
            replySender.replyMarkdown(sessionWebhook, "智能客服回复",
                ReplyAttachmentUtils.markdown(reply, images));
        }
    }

    private void replySafely(String sessionWebhook, String content, String msgId) {
        try {
            replySender.replyText(sessionWebhook, content);
        } catch (Exception e) {
            log.error("DingTalk Stream reply failed, msgId={}", msgId, e);
        }
    }

    private static String contentFrom(ChatbotMessage message) {
        MessageContent content = message.getText() != null ? message.getText() : message.getContent();
        return content == null ? "" : value(content.getContent()).trim();
    }

    private static DingTalkMediaRequest mediaFrom(ChatbotMessage message, String msgType) {
        MessageContent content = message.getContent() != null
            ? message.getContent() : message.getText();
        if ("richtext".equals(msgType)) {
            return pictureFromRichText(message, content);
        }
        if (!"picture".equals(msgType) && !"image".equals(msgType)
                && !"audio".equals(msgType) && !"voice".equals(msgType)) {
            return null;
        }
        String downloadCode = "";
        String recognition = "";
        String fileName = "";
        if (content != null) {
            downloadCode = firstNonBlank(
                value(content.getDownloadCode()), value(content.getPictureDownloadCode()));
            recognition = value(content.getRecognition()).trim();
            fileName = value(content.getFileName()).trim();
        }
        return new DingTalkMediaRequest(value(message.getMsgId()), msgType,
            downloadCode, recognition, fileName, null,
            captionFrom(message, content, false));
    }

    private static DingTalkMediaRequest pictureFromRichText(
            ChatbotMessage message, MessageContent content) {
        if (content == null || content.getRichText() == null) return null;
        String caption = richTextCaption(content);
        for (MessageContent item : content.getRichText()) {
            if (item == null) continue;
            String pictureCode = value(item.getPictureDownloadCode()).trim();
            String downloadCode = firstNonBlank(
                value(item.getDownloadCode()).trim(), pictureCode);
            if (("picture".equals(normalizeType(item.getType())) || !pictureCode.isBlank())
                    && !downloadCode.isBlank()) {
                return new DingTalkMediaRequest(value(message.getMsgId()), "picture",
                    downloadCode, "", value(item.getFileName()).trim(), null, caption);
            }
        }
        return null;
    }

    private static String captionFrom(ChatbotMessage message, MessageContent content,
                                      boolean richText) {
        if (richText) return richTextCaption(content);
        String caption = message.getText() == null
            ? "" : value(message.getText().getContent()).trim();
        if (caption.isBlank() && content != null) {
            caption = value(content.getContent()).trim();
        }
        return caption;
    }

    private static String richTextCaption(MessageContent content) {
        if (content == null || content.getRichText() == null) return "";
        StringBuilder caption = new StringBuilder();
        for (MessageContent item : content.getRichText()) {
            if (item == null) continue;
            String type = normalizeType(item.getType());
            if ("picture".equals(type) || "image".equals(type)
                    || "at".equals(type) || "mention".equals(type)) {
                continue;
            }
            String text = firstNonBlank(value(item.getText()), value(item.getContent())).trim();
            if (text.isBlank()) continue;
            if (caption.length() > 0) caption.append('\n');
            caption.append(text);
        }
        return caption.toString().trim();
    }

    private static String replyFrom(Map<String, Object> result) {
        if (result == null) return ERROR_REPLY;
        Object reply = result.get("reply");
        if (reply != null && !reply.toString().isBlank()) return reply.toString();
        return Boolean.TRUE.equals(result.get("duplicate")) ? DUPLICATE_REPLY : ERROR_REPLY;
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static Executor createExecutor(int workerThreads, int queueCapacity) {
        int threads = Math.max(1, workerThreads);
        int capacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity),
            runnable -> {
                Thread thread = new Thread(runnable, "dingtalk-media");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        if (managedExecutor == null) return;
        managedExecutor.shutdown();
        try {
            if (!managedExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                managedExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            managedExecutor.shutdownNow();
        }
    }
}
