package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.admin.entity.BotChannelConfig;
import com.feisheng.bot.admin.mapper.BotChannelConfigMapper;
import com.feisheng.bot.gateway.client.DingTalkClient;
import com.feisheng.bot.gateway.dto.DingTalkMediaRequest;
import com.feisheng.bot.gateway.service.DingTalkMediaProcessingException;
import com.feisheng.bot.gateway.service.DingTalkMediaProcessor;
import com.feisheng.bot.knowledge.service.MinioStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class DingTalkInboundMediaProcessor implements DingTalkMediaProcessor {
    private static final Logger log = LoggerFactory.getLogger(DingTalkInboundMediaProcessor.class);
    private static final Set<String> DIRECT_AUDIO_EXTENSIONS = Set.of("mp3", "wav");

    private final DingTalkClient dingTalkClient;
    private final BotChannelConfigMapper channelConfigMapper;
    private final ObjectMapper objectMapper;
    private final ImageOcrService imageOcrService;
    private final SpeechTranscriptionService speechTranscriptionService;
    private final MinioStorageService storageService;
    private final String environmentClientId;
    private final String environmentClientSecret;
    private final String environmentRobotCode;
    private final long maxImageBytes;
    private final long maxAudioBytes;
    private final int maxTextChars;
    private final String ffmpegCommand;
    private final long ffmpegTimeoutSeconds;

    public DingTalkInboundMediaProcessor(
            DingTalkClient dingTalkClient,
            BotChannelConfigMapper channelConfigMapper,
            ObjectMapper objectMapper,
            ImageOcrService imageOcrService,
            SpeechTranscriptionService speechTranscriptionService,
            MinioStorageService storageService,
            @Value("${dingtalk.stream.client-id:}") String environmentClientId,
            @Value("${dingtalk.stream.client-secret:${dingtalk.app-secret:}}") String environmentClientSecret,
            @Value("${dingtalk.robot-code:}") String environmentRobotCode,
            @Value("${dingtalk.media.max-image-bytes:10485760}") long maxImageBytes,
            @Value("${dingtalk.media.max-audio-bytes:26214400}") long maxAudioBytes,
            @Value("${dingtalk.media.max-text-chars:8000}") int maxTextChars,
            @Value("${dingtalk.media.ffmpeg-command:ffmpeg}") String ffmpegCommand,
            @Value("${dingtalk.media.ffmpeg-timeout-seconds:60}") long ffmpegTimeoutSeconds) {
        this.dingTalkClient = dingTalkClient;
        this.channelConfigMapper = channelConfigMapper;
        this.objectMapper = objectMapper;
        this.imageOcrService = imageOcrService;
        this.speechTranscriptionService = speechTranscriptionService;
        this.storageService = storageService;
        this.environmentClientId = environmentClientId;
        this.environmentClientSecret = environmentClientSecret;
        this.environmentRobotCode = environmentRobotCode;
        this.maxImageBytes = maxImageBytes;
        this.maxAudioBytes = maxAudioBytes;
        this.maxTextChars = Math.max(1, maxTextChars);
        this.ffmpegCommand = ffmpegCommand;
        this.ffmpegTimeoutSeconds = Math.max(1, ffmpegTimeoutSeconds);
    }

    @Override
    public String normalize(DingTalkMediaRequest request) {
        if (request == null) {
            throw new DingTalkMediaProcessingException("未收到可处理的媒体消息");
        }
        return switch (normalizeType(request.msgType())) {
            case "picture", "image" -> normalizeImage(request);
            case "audio", "voice" -> normalizeAudio(request);
            default -> throw new DingTalkMediaProcessingException("暂不支持该类型的媒体消息");
        };
    }

    @Override
    public MediaResult process(DingTalkMediaRequest request) {
        if (request == null) throw new DingTalkMediaProcessingException("未收到可处理的媒体消息");
        String type = normalizeType(request.msgType());
        if ("picture".equals(type) || "image".equals(type)) {
            return processImage(request);
        }
        return new MediaResult(normalize(request), "text", null);
    }

    private MediaResult processImage(DingTalkMediaRequest request) {
        DingTalkClient.DownloadedMedia media = download(request, maxImageBytes);
        String extension = imageExtension(firstText(request.fileName(), media.fileName()),
            media.contentType(), media.content());
        Path image = null;
        try {
            image = Files.createTempFile("feisheng-dingtalk-image-", "." + extension);
            Files.write(image, media.content());
            String metadata = storeImage(request, media, image, extension);
            ImageOcrService.OcrResult result = imageOcrService.extract(
                image, "dingtalk-image." + extension);
            return new MediaResult(withCaption(request,
                marked("客户发送了一张图片，以下为图片中的文字", result.text())),
                "image", metadata);
        } catch (DingTalkMediaProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new DingTalkMediaProcessingException(
                "暂时无法识别这张图片，请发送包含清晰文字的图片或补充文字说明", e);
        } finally {
            deleteQuietly(image);
        }
    }

    private String storeImage(DingTalkMediaRequest request,
                              DingTalkClient.DownloadedMedia media,
                              Path image, String extension) {
        String fileName = firstText(request.fileName(), media.fileName(),
            "dingtalk-image." + extension);
        String contentType = imageContentType(extension, media.contentType());
        try {
            MinioStorageService.UploadResult stored = storageService.upload(
                image, fileName, contentType);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "dingtalk");
            metadata.put("mediaType", "image");
            metadata.put("bucket", stored.bucketName());
            metadata.put("objectKey", stored.objectKey());
            metadata.put("fileName", fileName);
            metadata.put("contentType", contentType);
            metadata.put("size", stored.fileSize());
            metadata.put("msgId", request.msgId());
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Could not persist DingTalk inbound image, msgId={}: {}",
                request.msgId(), e.getMessage());
            return null;
        }
    }

    private String normalizeImage(DingTalkMediaRequest request) {
        DingTalkClient.DownloadedMedia media = download(request, maxImageBytes);
        String extension = imageExtension(firstText(request.fileName(), media.fileName()),
            media.contentType(), media.content());
        Path image = null;
        try {
            image = Files.createTempFile("feisheng-dingtalk-image-", "." + extension);
            Files.write(image, media.content());
            ImageOcrService.OcrResult result = imageOcrService.extract(
                image, "dingtalk-image." + extension);
            return withCaption(request,
                marked("客户发送了一张图片，以下为图片中的文字", result.text()));
        } catch (DingTalkMediaProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new DingTalkMediaProcessingException(
                "暂时无法识别这张图片，请发送包含清晰文字的图片或补充文字说明", e);
        } finally {
            deleteQuietly(image);
        }
    }

    private String normalizeAudio(DingTalkMediaRequest request) {
        String recognition = clean(request.recognition());
        if (!recognition.isBlank()) {
            return withCaption(request,
                marked("客户发送了一条语音，以下为语音识别内容", recognition));
        }

        DingTalkClient.DownloadedMedia media = download(request, maxAudioBytes);
        String extension = audioExtension(firstText(request.fileName(), media.fileName()),
            media.contentType());
        Path source = null;
        Path converted = null;
        try {
            source = Files.createTempFile("feisheng-dingtalk-audio-", "." + extension);
            Files.write(source, media.content());
            Path transcriptionInput = source;
            String transcriptionName = "dingtalk-audio." + extension;
            if (!DIRECT_AUDIO_EXTENSIONS.contains(extension)) {
                converted = convertToWav(source);
                transcriptionInput = converted;
                transcriptionName = "dingtalk-audio.wav";
            }
            SpeechTranscriptionService.TranscriptionResult result =
                speechTranscriptionService.transcribe(transcriptionInput, transcriptionName);
            return withCaption(request,
                marked("客户发送了一条语音，以下为语音转写内容", result.text()));
        } catch (DingTalkMediaProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new DingTalkMediaProcessingException(
                "暂时无法识别这条语音，请稍后重试或改用文字发送", e);
        } finally {
            deleteQuietly(converted);
            deleteQuietly(source);
        }
    }

    private DingTalkClient.DownloadedMedia download(DingTalkMediaRequest request, long maxBytes) {
        if (!hasText(request.downloadCode())) {
            throw new DingTalkMediaProcessingException("媒体消息缺少下载凭证，请重新发送");
        }
        Credentials credentials = credentials(request.robotCode());
        try {
            return dingTalkClient.downloadRobotMessageFile(
                credentials.appKey(), credentials.appSecret(), credentials.robotCode(),
                request.downloadCode(), maxBytes);
        } catch (Exception e) {
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage().replaceAll("\\s+", " ").trim();
            log.warn("DingTalk media download failed, msgId={}: {}", request.msgId(), reason);
            throw new DingTalkMediaProcessingException("媒体文件下载失败，请重新发送", e);
        }
    }

    private Credentials credentials(String messageRobotCode) {
        Map<String, Object> config = latestConfig();
        String appKey = firstText(config.get("clientId"), config.get("appKey"),
            environmentClientId);
        String appSecret = firstText(config.get("clientSecret"), config.get("appSecret"),
            environmentClientSecret);
        String robotCode = firstText(messageRobotCode, config.get("robotCode"),
            environmentRobotCode, appKey);
        if (!hasText(appKey) || !hasText(appSecret) || !hasText(robotCode)) {
            throw new DingTalkMediaProcessingException("钉钉媒体下载配置不完整，请联系管理员");
        }
        return new Credentials(appKey, appSecret, robotCode);
    }

    private Map<String, Object> latestConfig() {
        BotChannelConfig config = channelConfigMapper.selectOne(
            new LambdaQueryWrapper<BotChannelConfig>()
                .eq(BotChannelConfig::getChannelType, "dingtalk")
                .eq(BotChannelConfig::getStatus, 1)
                .orderByDesc(BotChannelConfig::getId)
                .last("LIMIT 1"));
        if (config == null || !hasText(config.getConfigJson())) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(config.getConfigJson(), new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private Path convertToWav(Path source) {
        Path target = null;
        Process process = null;
        try {
            if (!hasText(ffmpegCommand)) {
                throw new IOException("FFmpeg command is blank");
            }
            target = Files.createTempFile("feisheng-dingtalk-audio-", ".wav");
            process = new ProcessBuilder(
                ffmpegCommand, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-i", source.toString(), "-ac", "1", "-ar", "16000",
                "-f", "wav", target.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(ffmpegTimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new DingTalkMediaProcessingException("语音格式转换超时，请改用文字发送");
            }
            if (process.exitValue() != 0 || Files.size(target) <= 44) {
                throw new DingTalkMediaProcessingException("语音格式无法识别，请改用文字发送");
            }
            return target;
        } catch (DingTalkMediaProcessingException e) {
            deleteQuietly(target);
            throw e;
        } catch (IOException e) {
            deleteQuietly(target);
            throw new DingTalkMediaProcessingException(
                "语音格式转换服务不可用，请联系管理员", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(target);
            throw new DingTalkMediaProcessingException("语音格式转换被中断，请重新发送", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private String marked(String label, String text) {
        String value = clean(text);
        if (value.isBlank()) {
            throw new DingTalkMediaProcessingException("媒体内容中未识别到可用文字");
        }
        if (value.length() > maxTextChars) {
            value = value.substring(0, maxTextChars) + "\n[内容已截断]";
        }
        return "[" + label + "]\n" + value;
    }

    private String withCaption(DingTalkMediaRequest request, String normalizedMedia) {
        String caption = clean(request.caption());
        if (caption.isBlank()) return normalizedMedia;
        if (caption.length() > maxTextChars) {
            caption = caption.substring(0, maxTextChars) + "\n[内容已截断]";
        }
        return "[客户附带问题]\n" + caption + "\n\n" + normalizedMedia;
    }

    private String imageExtension(String fileName, String contentType, byte[] content) {
        String extension = extension(fileName);
        if (imageOcrService.supports("image." + extension)) return extension;
        String type = normalizeContentType(contentType);
        if (type.contains("png")) return "png";
        if (type.contains("jpeg") || type.contains("jpg")) return "jpg";
        if (type.contains("bmp")) return "bmp";
        if (type.contains("tiff")) return "tiff";
        if (content != null && content.length >= 4) {
            if ((content[0] & 0xff) == 0x89 && content[1] == 0x50
                    && content[2] == 0x4e && content[3] == 0x47) return "png";
            if ((content[0] & 0xff) == 0xff && (content[1] & 0xff) == 0xd8) return "jpg";
            if (content[0] == 0x42 && content[1] == 0x4d) return "bmp";
        }
        return "jpg";
    }

    private String audioExtension(String fileName, String contentType) {
        String extension = extension(fileName);
        if (!extension.isBlank()) return extension;
        String type = normalizeContentType(contentType);
        if (type.contains("mpeg") || type.contains("mp3")) return "mp3";
        if (type.contains("wav")) return "wav";
        if (type.contains("ogg")) return "ogg";
        if (type.contains("webm")) return "webm";
        if (type.contains("mp4") || type.contains("m4a")) return "m4a";
        return "amr";
    }

    private String extension(String fileName) {
        if (!hasText(fileName)) return "";
        String value = fileName.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot == value.length() - 1) return "";
        value = value.substring(dot + 1).toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9]{1,8}") ? value : "";
    }

    private String normalizeType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String imageContentType(String extension, String detected) {
        String type = normalizeContentType(detected);
        if (type.startsWith("image/")) return type;
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            default -> "image/jpeg";
        };
    }

    private String clean(String value) {
        if (value == null) return "";
        return value.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("[ \\t]+(?=\\n)", "")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) return value.toString().trim();
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private record Credentials(String appKey, String appSecret, String robotCode) {
    }
}
