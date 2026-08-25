package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotConversation;
import com.feisheng.bot.admin.entity.BotMessage;
import com.feisheng.bot.admin.entity.BotTicket;
import com.feisheng.bot.admin.entity.BotTicketRecord;
import com.feisheng.bot.admin.entity.SysUser;
import com.feisheng.bot.admin.mapper.BotConversationMapper;
import com.feisheng.bot.admin.mapper.BotMessageMapper;
import com.feisheng.bot.admin.mapper.BotTicketMapper;
import com.feisheng.bot.admin.mapper.BotTicketRecordMapper;
import com.feisheng.bot.admin.mapper.SysUserMapper;
import com.feisheng.bot.common.exception.BusinessException;
import com.feisheng.bot.core.service.SensitiveDataService;
import com.feisheng.bot.gateway.service.DingTalkImageReplyDispatcher.ReplyTarget;
import com.feisheng.bot.gateway.util.DingTalkReplyTargetMetadata;
import com.feisheng.bot.gateway.util.ReplyAttachmentUtils;
import com.feisheng.bot.knowledge.service.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class HumanHandoffService {
    private static final Logger log = LoggerFactory.getLogger(HumanHandoffService.class);
    private static final Set<String> TERMINAL_STATUSES = Set.of("resolved", "closed");
    private static final int MAX_REPLY_CHARS = 4000;
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024L;

    private final BotTicketMapper ticketMapper;
    private final BotTicketRecordMapper recordMapper;
    private final BotConversationMapper conversationMapper;
    private final BotMessageMapper messageMapper;
    private final SysUserMapper userMapper;
    private final ChannelReplyDispatcher replyDispatcher;
    private final SensitiveDataService sensitiveDataService;
    private final ObjectMapper objectMapper;
    private final MinioStorageService storageService;
    private final ConversationImageService imageService;

    public HumanHandoffService(
            BotTicketMapper ticketMapper,
            BotTicketRecordMapper recordMapper,
            BotConversationMapper conversationMapper,
            BotMessageMapper messageMapper,
            SysUserMapper userMapper,
            ChannelReplyDispatcher replyDispatcher,
            SensitiveDataService sensitiveDataService,
            ObjectMapper objectMapper,
            MinioStorageService storageService,
            ConversationImageService imageService) {
        this.ticketMapper = ticketMapper;
        this.recordMapper = recordMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.userMapper = userMapper;
        this.replyDispatcher = replyDispatcher;
        this.sensitiveDataService = sensitiveDataService;
        this.objectMapper = objectMapper;
        this.storageService = storageService;
        this.imageService = imageService;
    }

    public Page<BotTicket> list(int page, int size, String status, Long assigneeId,
                                String channelType, String customerName) {
        Page<BotTicket> result = ticketMapper.selectAdminPage(
            new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
            normalized(status, true), assigneeId,
            normalized(channelType, true), normalized(customerName, false));
        enrichAssigneeNames(result.getRecords());
        return result;
    }

    public BotTicket get(Long ticketId) {
        BotTicket ticket = requireTicket(ticketId, false);
        enrichAssigneeNames(List.of(ticket));
        return ticket;
    }

    public BotTicket findByConversation(Long conversationId) {
        BotTicket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<BotTicket>()
            .eq(BotTicket::getConversationId, conversationId)
            .orderByDesc(BotTicket::getId)
            .last("LIMIT 1"));
        if (ticket != null) enrichAssigneeNames(List.of(ticket));
        return ticket;
    }

    public List<BotTicketRecord> records(Long ticketId) {
        return recordMapper.selectList(new LambdaQueryWrapper<BotTicketRecord>()
            .eq(BotTicketRecord::getTicketId, ticketId)
            .orderByAsc(BotTicketRecord::getId));
    }

    @Transactional
    public BotTicket claim(Long ticketId, Long operatorId) {
        BotTicket ticket = requireTicket(ticketId, true);
        ensureOpen(ticket);
        if (ticket.getAssigneeId() != null && !ticket.getAssigneeId().equals(operatorId)) {
            throw new BusinessException(409, "工单已被其他客服接管");
        }
        SysUser operator = requireOperator(operatorId);
        boolean firstClaim = ticket.getAssigneeId() == null;
        Date now = new Date();
        ticket.setAssigneeId(operatorId);
        ticket.setStatus("processing");
        if (ticket.getAcceptedTime() == null) ticket.setAcceptedTime(now);
        ticketMapper.updateById(ticket);

        BotConversation conversation = requireConversation(ticket.getConversationId());
        conversation.setStatus("transferred");
        conversation.setHandoffStatus("PROCESSING");
        conversation.setAssignedAgentId(operatorId);
        conversation.setAssignedAgentName(displayName(operator));
        if (conversation.getAcceptedTime() == null) conversation.setAcceptedTime(now);
        conversationMapper.updateById(conversation);
        if (firstClaim) addRecord(ticketId, operatorId, "CLAIM", displayName(operator) + " 接管工单");
        ticket.setAssigneeName(displayName(operator));
        return ticket;
    }

    @Transactional
    public ReplyResult reply(Long ticketId, Long operatorId, String content) {
        return reply(ticketId, operatorId, content, null);
    }

    @Transactional
    public ReplyResult reply(Long ticketId, Long operatorId, String content,
                             Long replyToMessageId) {
        if (!hasText(content)) throw new BusinessException(400, "回复内容不能为空");
        String normalized = content.trim();
        if (normalized.length() > MAX_REPLY_CHARS) {
            throw new BusinessException(400, "回复内容不能超过 " + MAX_REPLY_CHARS + " 个字符");
        }
        BotTicket ticket = requireTicket(ticketId, true);
        ensureOpen(ticket);
        SysUser operator = requireOperator(operatorId);
        boolean firstClaim = claimIfNeeded(ticket, operator);
        BotConversation conversation = requireConversation(ticket.getConversationId());
        BotMessage customerMessage = requireUnansweredCustomerMessage(
            conversation.getId(), replyToMessageId);

        SensitiveDataService.RedactionResult redaction = sensitiveDataService.redact(normalized);
        String safeContent = redaction.text();
        String agentName = displayName(operator);
        ReplyTarget replyTarget = dingTalkTarget(conversation, customerMessage);
        ChannelReplyDispatcher.DispatchResult dispatch = replyTarget == null
            ? replyDispatcher.dispatch(conversation, safeContent)
            : replyDispatcher.dispatch(conversation, safeContent, replyTarget);
        Date now = new Date();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentId", operatorId);
        metadata.put("agentName", agentName);
        metadata.put("replyToMessageId", customerMessage.getId());
        metadata.put("deliveryStatus", dispatch.status());
        metadata.put("deliveryChannel", dispatch.channel());
        metadata.put("deliveryError", dispatch.error());
        metadata.put("redactionApplied", !redaction.types().isEmpty());

        BotMessage message = new BotMessage();
        message.setConversationId(conversation.getId());
        message.setRole("human");
        message.setContentType("text");
        message.setContent(safeContent);
        message.setMetadata(toJson(metadata));
        messageMapper.insert(message);

        ticket.setStatus("processing");
        ticket.setLastReplyTime(now);
        ticketMapper.updateById(ticket);
        conversation.setStatus("transferred");
        conversation.setHandoffStatus("PROCESSING");
        conversation.setAssignedAgentId(operatorId);
        conversation.setAssignedAgentName(displayName(operator));
        conversation.setLastHumanReplyTime(now);
        if (conversation.getAcceptedTime() == null) conversation.setAcceptedTime(now);
        conversationMapper.updateById(conversation);
        if (firstClaim) addRecord(ticketId, operatorId, "CLAIM", displayName(operator) + " 接管工单");
        addRecord(ticketId, operatorId, dispatch.delivered() ? "REPLY" : "REPLY_FAILED",
            dispatch.delivered() ? safeContent : safeContent + "\n发送失败：" + dispatch.error());
        return new ReplyResult(dispatch.delivered(), dispatch.status(), dispatch.channel(),
            dispatch.error(), message.getId());
    }

    @Transactional
    public ReplyResult replyImage(Long ticketId, Long operatorId, MultipartFile file) {
        return replyImage(ticketId, operatorId, null, null, file);
    }

    @Transactional
    public ReplyResult replyImage(Long ticketId, Long operatorId, String content,
                                  Long replyToMessageId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要发送的图片");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new BusinessException(400, "图片不能超过 10MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new BusinessException(400, "只支持发送图片文件");
        }
        String normalizedContent = hasText(content) ? content.trim() : null;
        if (normalizedContent != null && normalizedContent.length() > MAX_REPLY_CHARS) {
            throw new BusinessException(400, "回复内容不能超过 " + MAX_REPLY_CHARS + " 个字符");
        }

        BotTicket ticket = requireTicket(ticketId, true);
        ensureOpen(ticket);
        SysUser operator = requireOperator(operatorId);
        boolean firstClaim = claimIfNeeded(ticket, operator);
        BotConversation conversation = requireConversation(ticket.getConversationId());
        if (!"dingtalk".equalsIgnoreCase(normalized(conversation.getChannelType(), true))) {
            throw new BusinessException(400, "当前仅支持钉钉渠道发送图片");
        }
        BotMessage customerMessage = requireUnansweredCustomerMessage(
            conversation.getId(), replyToMessageId);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(400, "图片读取失败，请重新选择");
        }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            throw new BusinessException(400, "图片不能超过 10MB");
        }
        String fileName = imageFileName(file.getOriginalFilename());
        MinioStorageService.UploadResult storedImage = storeImage(
            bytes, fileName, contentType);
        SensitiveDataService.RedactionResult redaction =
            sensitiveDataService.redact(normalizedContent);
        String safeContent = redaction.text();
        String agentName = displayName(operator);
        Date now = new Date();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentId", operatorId);
        metadata.put("agentName", agentName);
        metadata.put("replyToMessageId", customerMessage.getId());
        metadata.put("redactionApplied", !redaction.types().isEmpty());
        metadata.put("fileName", fileName);
        metadata.put("contentType", contentType);
        metadata.put("size", bytes.length);
        if (storedImage != null) {
            metadata.put("source", "human");
            metadata.put("mediaType", "image");
            metadata.put("bucket", storedImage.bucketName());
            metadata.put("objectKey", storedImage.objectKey());
            metadata.put("previewAvailable", true);
        } else {
            metadata.put("previewAvailable", false);
        }

        BotMessage message = new BotMessage();
        message.setConversationId(conversation.getId());
        message.setRole("human");
        message.setContentType(hasText(safeContent) ? "mixed" : "image");
        message.setContent(hasText(safeContent) ? safeContent : fileName);
        message.setMetadata(toJson(metadata));
        messageMapper.insert(message);

        String imageUrl = storedImage == null ? null : imageService.url(message);
        ReplyTarget replyTarget = dingTalkTarget(conversation, customerMessage);
        ChannelReplyDispatcher.DispatchResult dispatch = dispatchImageReply(
            conversation, replyTarget, safeContent, imageUrl,
            bytes, fileName, contentType, message.getId());
        metadata.put("deliveryStatus", dispatch.status());
        metadata.put("deliveryChannel", dispatch.channel());
        metadata.put("deliveryError", dispatch.error());
        message.setMetadata(toJson(metadata));
        messageMapper.updateById(message);

        ticket.setStatus("processing");
        ticket.setLastReplyTime(now);
        ticketMapper.updateById(ticket);
        conversation.setStatus("transferred");
        conversation.setHandoffStatus("PROCESSING");
        conversation.setAssignedAgentId(operatorId);
        conversation.setAssignedAgentName(agentName);
        conversation.setLastHumanReplyTime(now);
        if (conversation.getAcceptedTime() == null) conversation.setAcceptedTime(now);
        conversationMapper.updateById(conversation);
        if (firstClaim) addRecord(ticketId, operatorId, "CLAIM", agentName + " 接管工单");
        String recordContent = hasText(safeContent)
            ? safeContent + "\n图片：" + fileName : "图片：" + fileName;
        addRecord(ticketId, operatorId, dispatch.delivered() ? "REPLY" : "REPLY_FAILED",
            dispatch.delivered() ? recordContent
                : recordContent + "\n发送失败：" + dispatch.error());
        return new ReplyResult(dispatch.delivered(), dispatch.status(), dispatch.channel(),
            dispatch.error(), message.getId());
    }

    @Transactional
    public ResolveResult resolve(Long ticketId, Long operatorId, String resolution) {
        BotTicket ticket = requireTicket(ticketId, true);
        ensureOpen(ticket);
        SysUser operator = requireOperator(operatorId);
        claimIfNeeded(ticket, operator);
        if (!Objects.equals(ticket.getAssigneeId(), operatorId)) {
            throw new BusinessException(409, "只有接管该工单的客服可以解决工单");
        }
        SensitiveDataService.RedactionResult redaction = sensitiveDataService.redact(
            hasText(resolution) ? resolution.trim() : "问题已解决");
        Date now = new Date();
        ticket.setStatus("resolved");
        ticket.setResolvedTime(now);
        ticket.setResolution(redaction.text());
        ticketMapper.updateById(ticket);

        BotConversation conversation = requireConversation(ticket.getConversationId());
        conversation.setStatus("closed");
        conversation.setHandoffStatus("RESOLVED");
        conversation.setAssignedAgentId(operatorId);
        conversation.setAssignedAgentName(displayName(operator));
        if (conversation.getAcceptedTime() == null) conversation.setAcceptedTime(now);
        conversation.setResolvedTime(now);
        conversationMapper.updateById(conversation);

        BotMessage systemMessage = new BotMessage();
        systemMessage.setConversationId(conversation.getId());
        systemMessage.setRole("system");
        systemMessage.setContentType("text");
        systemMessage.setContent("【系统通知】人工客服已解决本次会话");
        systemMessage.setMetadata(toJson(Map.of(
            "ticketId", ticketId, "operatorId", operatorId, "event", "RESOLVED")));
        messageMapper.insert(systemMessage);
        addRecord(ticketId, operatorId, "RESOLVE", redaction.text());
        return new ResolveResult(true, true, conversation.getId());
    }

    private boolean claimIfNeeded(BotTicket ticket, SysUser operator) {
        if (ticket.getAssigneeId() != null && !ticket.getAssigneeId().equals(operator.getId())) {
            throw new BusinessException(409, "工单已被其他客服接管");
        }
        if (ticket.getAssigneeId() != null) return false;
        Date now = new Date();
        ticket.setAssigneeId(operator.getId());
        ticket.setAcceptedTime(now);
        ticket.setStatus("processing");
        return true;
    }

    private BotTicket requireTicket(Long ticketId, boolean lock) {
        if (ticketId == null) throw new BusinessException(400, "工单 ID 不能为空");
        LambdaQueryWrapper<BotTicket> query = new LambdaQueryWrapper<BotTicket>()
            .eq(BotTicket::getId, ticketId);
        if (lock) query.last("FOR UPDATE");
        BotTicket ticket = ticketMapper.selectOne(query);
        if (ticket == null) throw new BusinessException(404, "工单不存在");
        return ticket;
    }

    private BotConversation requireConversation(Long conversationId) {
        BotConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) throw new BusinessException(404, "会话不存在");
        return conversation;
    }

    private SysUser requireOperator(Long operatorId) {
        if (operatorId == null) throw new BusinessException(401, "登录状态无效");
        SysUser user = userMapper.selectById(operatorId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new BusinessException(403, "客服账号不可用");
        }
        return user;
    }

    private void ensureOpen(BotTicket ticket) {
        if (TERMINAL_STATUSES.contains(ticket.getStatus())) {
            throw new BusinessException(409, "工单已经结束");
        }
    }

    private void addRecord(Long ticketId, Long operatorId, String action, String content) {
        BotTicketRecord record = new BotTicketRecord();
        record.setTicketId(ticketId);
        record.setOperatorId(operatorId);
        record.setAction(action);
        record.setContent(content);
        recordMapper.insert(record);
    }

    private void enrichAssigneeNames(List<BotTicket> tickets) {
        Set<Long> ids = tickets.stream().map(BotTicket::getAssigneeId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return;
        Map<Long, String> names = userMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(SysUser::getId, this::displayName));
        tickets.forEach(ticket -> ticket.setAssigneeName(names.get(ticket.getAssigneeId())));
    }

    private BotMessage requireUnansweredCustomerMessage(Long conversationId,
                                                         Long requestedMessageId) {
        LambdaQueryWrapper<BotMessage> customerQuery = new LambdaQueryWrapper<BotMessage>()
            .eq(BotMessage::getConversationId, conversationId)
            .eq(BotMessage::getRole, "user");
        if (requestedMessageId == null) {
            customerQuery.orderByDesc(BotMessage::getId).last("LIMIT 1");
        } else {
            customerQuery.eq(BotMessage::getId, requestedMessageId);
        }
        BotMessage customerMessage = messageMapper.selectOne(customerQuery);
        if (customerMessage == null) {
            throw new BusinessException(409, "当前没有待回复的客户消息");
        }
        BotMessage existingReply = messageMapper.selectOne(
            new LambdaQueryWrapper<BotMessage>()
                .eq(BotMessage::getConversationId, conversationId)
                .gt(BotMessage::getId, customerMessage.getId())
                .in(BotMessage::getRole, "ai", "assistant", "human")
                .orderByAsc(BotMessage::getId)
                .last("LIMIT 1"));
        if (existingReply != null) {
            throw new BusinessException(409, "该客户消息已经回复，请等待客户发送新消息");
        }
        return customerMessage;
    }

    private String displayName(SysUser user) {
        return hasText(user.getRealName()) ? user.getRealName().trim() : user.getUsername();
    }

    private String imageFileName(String originalName) {
        String value = hasText(originalName) ? originalName.replace('\\', '/') : "image";
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        return hasText(value) ? value : "image";
    }

    private ReplyTarget dingTalkTarget(BotConversation conversation,
                                       BotMessage customerMessage) {
        if (conversation == null || !"dingtalk".equalsIgnoreCase(
                normalized(conversation.getChannelType(), true))) {
            return null;
        }
        if (customerMessage == null || !hasText(customerMessage.getMetadata())) return null;
        ReplyTarget target = DingTalkReplyTargetMetadata.readTarget(
            objectMapper, customerMessage.getMetadata());
        return DingTalkReplyTargetMetadata.hasTarget(target) ? target : null;
    }

    private ChannelReplyDispatcher.DispatchResult dispatchImageReply(
            BotConversation conversation, ReplyTarget replyTarget,
            String content, String imageUrl, byte[] image,
            String fileName, String contentType, Long messageId) {
        if (hasText(content) && isPublicHttpUrl(imageUrl)) {
            String markdown = ReplyAttachmentUtils.markdown(content, List.of(
                new ReplyAttachmentUtils.ImageAttachment(messageId, fileName, imageUrl)));
            return replyDispatcher.dispatchMarkdown(
                conversation, "客服回复", markdown, replyTarget);
        }

        ChannelReplyDispatcher.DispatchResult textDispatch = hasText(content)
            ? dispatchText(conversation, content, replyTarget) : null;
        ChannelReplyDispatcher.DispatchResult imageDispatch = replyTarget == null
            ? replyDispatcher.dispatchImage(conversation, image, fileName, contentType)
            : replyDispatcher.dispatchImage(
                conversation, image, fileName, contentType, replyTarget);
        if (textDispatch == null || textDispatch.delivered()) {
            return imageDispatch;
        }
        if (imageDispatch.delivered()) {
            return new ChannelReplyDispatcher.DispatchResult(false, "FAILED",
                imageDispatch.channel(), textDispatch.error());
        }
        return new ChannelReplyDispatcher.DispatchResult(false, "FAILED",
            imageDispatch.channel(), textDispatch.error() + "；" + imageDispatch.error());
    }

    private ChannelReplyDispatcher.DispatchResult dispatchText(
            BotConversation conversation, String content, ReplyTarget replyTarget) {
        return replyTarget == null
            ? replyDispatcher.dispatch(conversation, content)
            : replyDispatcher.dispatch(conversation, content, replyTarget);
    }

    private boolean isPublicHttpUrl(String value) {
        return hasText(value) && (value.startsWith("https://") || value.startsWith("http://"));
    }

    private MinioStorageService.UploadResult storeImage(byte[] bytes,
                                                         String fileName,
                                                         String contentType) {
        try {
            return storageService.upload(bytes, fileName, contentType);
        } catch (Exception e) {
            log.warn("Could not persist human reply image {}: {}", fileName, e.getMessage());
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalized(String value, boolean lowerCase) {
        if (!hasText(value)) return null;
        String normalized = value.trim();
        return lowerCase ? normalized.toLowerCase() : normalized;
    }

    public record ReplyResult(boolean delivered, String deliveryStatus,
                              String channel, String error, Long messageId) {}

    public record ResolveResult(boolean resolved, boolean csatRequested,
                                Long conversationId) {}
}
