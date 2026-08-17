package com.feisheng.bot.gateway.util;

import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import com.feisheng.bot.core.service.RichReplyFormatter;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ReplyAttachmentUtils {
    private ReplyAttachmentUtils() {}

    public static List<ImageAttachment> images(Map<String, Object> result) {
        if (result == null || !(result.get("attachments") instanceof Iterable<?> values)) {
            return List.of();
        }
        List<ImageAttachment> images = new ArrayList<>();
        for (Object value : values) {
            ImageAttachment image = image(value);
            if (image != null) images.add(image);
        }
        return List.copyOf(images);
    }

    public static List<ImageAttachment> publicImages(Map<String, Object> result) {
        return images(result).stream().filter(image -> isPublicHttpUrl(image.url())).toList();
    }

    /** Returns rich content only when Core explicitly supplied useful Markdown. */
    public static String richReply(Map<String, Object> result) {
        if (result == null) return "";
        String value = text(result.get("richReply"));
        return RichReplyFormatter.isRich(value) ? RichReplyFormatter.format(value) : "";
    }

    public static String markdown(String reply, List<ImageAttachment> images) {
        StringBuilder value = new StringBuilder(reply == null ? "" : reply.trim());
        for (ImageAttachment image : images) {
            if (!value.isEmpty()) value.append("\n\n");
            value.append("![")
                .append(escapeLabel(image.title()))
                .append("](")
                .append(image.url())
                .append(')');
        }
        return value.toString();
    }

    private static ImageAttachment image(Object value) {
        if (value instanceof KnowledgeImageService.ImageAttachment attachment) {
            return new ImageAttachment(attachment.documentId(), attachment.title(), attachment.url());
        }
        if (!(value instanceof Map<?, ?> map)
                || !"image".equalsIgnoreCase(text(map.get("type")))) {
            return null;
        }
        Long documentId = longValue(map.get("documentId"));
        String url = text(map.get("url"));
        if (documentId == null || url.isBlank()) return null;
        return new ImageAttachment(documentId, text(map.get("title")), url);
    }

    private static boolean isPublicHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute()
                && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String escapeLabel(String value) {
        String title = value == null || value.isBlank() ? "知识库图片" : value;
        return title.replace("[", "\\[").replace("]", "\\]")
            .replace("\r", " ").replace("\n", " ");
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    public record ImageAttachment(Long documentId, String title, String url) {}
}
