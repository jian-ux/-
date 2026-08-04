package com.feisheng.bot.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feisheng.bot.admin.entity.BotAiModelConfig;
import com.feisheng.bot.admin.mapper.BotAiModelConfigMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Service
public class SpeechTranscriptionService {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "wav", "webm");
    private static final Set<String> GLM_ASR_EXTENSIONS = Set.of("mp3", "wav");

    private final BotAiModelConfigMapper modelMapper;
    private final SpeechTranscriptionClient client;
    private final boolean fallbackEnabled;
    private final String fallbackApiUrl;
    private final String fallbackApiKey;
    private final String fallbackModel;
    private final String language;
    private final String prompt;
    private final long maxBytes;

    public SpeechTranscriptionService(
            BotAiModelConfigMapper modelMapper,
            SpeechTranscriptionClient client,
            @Value("${speech.transcription.enabled:false}") boolean fallbackEnabled,
            @Value("${speech.transcription.api-url:https://api.openai.com/v1/audio/transcriptions}") String fallbackApiUrl,
            @Value("${speech.transcription.api-key:}") String fallbackApiKey,
            @Value("${speech.transcription.model:whisper-1}") String fallbackModel,
            @Value("${speech.transcription.language:zh}") String language,
            @Value("${speech.transcription.prompt:}") String prompt,
            @Value("${speech.transcription.max-bytes:26214400}") long maxBytes) {
        this.modelMapper = modelMapper;
        this.client = client;
        this.fallbackEnabled = fallbackEnabled;
        this.fallbackApiUrl = fallbackApiUrl;
        this.fallbackApiKey = fallbackApiKey;
        this.fallbackModel = fallbackModel;
        this.language = language;
        this.prompt = prompt;
        this.maxBytes = maxBytes;
    }

    public boolean supports(String fileName) {
        return SUPPORTED_EXTENSIONS.contains(extension(fileName));
    }

    public TranscriptionResult transcribe(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new SpeechException(400, "音频不能为空");
        }
        String originalName = safeFileName(file.getOriginalFilename());
        if (file.getSize() <= 0 || file.getSize() > maxBytes) {
            throw new SpeechException(400,
                "音频大小必须在 1 字节到 " + maxBytes + " 字节之间");
        }
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(
                "feisheng-speech-", "." + extension(originalName));
            file.transferTo(tempFile.toFile());
            return transcribe(tempFile, originalName);
        } catch (SpeechException e) {
            throw e;
        } catch (IOException e) {
            throw new SpeechException(500, "读取音频失败: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {}
            }
        }
    }

    public TranscriptionResult transcribe(Path audioPath, String fileName) {
        if (audioPath == null || !Files.isRegularFile(audioPath)) {
            throw new SpeechException(400, "音频不能为空");
        }
        String originalName = safeFileName(fileName);
        String extension = extension(originalName);
        if (!supports(originalName)) {
            throw new SpeechException(400,
                "不支持的音频格式，仅支持 flac、mp3、mp4、mpeg、mpga、m4a、ogg、wav、webm");
        }

        long started = System.currentTimeMillis();
        try {
            long size = Files.size(audioPath);
            if (size <= 0 || size > maxBytes) {
                throw new SpeechException(400,
                    "音频大小必须在 1 字节到 " + maxBytes + " 字节之间");
            }
            SpeechConfig config = resolveConfig();
            if ("glm-asr-2512".equalsIgnoreCase(config.model())
                    && !GLM_ASR_EXTENSIONS.contains(extension)) {
                throw new SpeechException(400, "当前语音模型仅支持 wav、mp3 音频");
            }
            validateAudioHeader(audioPath);
            String text = normalizeText(client.transcribe(
                audioPath, originalName, contentType(extension), config));
            if (!StringUtils.hasText(text)) {
                throw new SpeechException(422, "音频中未识别到可用文字");
            }
            return new TranscriptionResult(text, config.model(), config.provider(),
                config.language(), size, System.currentTimeMillis() - started);
        } catch (SpeechException e) {
            throw e;
        } catch (IOException e) {
            throw new SpeechException(500, "读取音频失败: " + e.getMessage(), e);
        }
    }

    public SpeechStatus status() {
        try {
            SpeechConfig config = resolveConfig();
            return new SpeechStatus(true, config.provider(), config.model(),
                maxBytes, supportedExtensions(config), null);
        } catch (SpeechException e) {
            return new SpeechStatus(false, null, null,
                maxBytes, SUPPORTED_EXTENSIONS, e.getMessage());
        }
    }

    private Set<String> supportedExtensions(SpeechConfig config) {
        return "glm-asr-2512".equalsIgnoreCase(config.model())
            ? GLM_ASR_EXTENSIONS : SUPPORTED_EXTENSIONS;
    }

    private SpeechConfig resolveConfig() {
        BotAiModelConfig configured = modelMapper.selectOne(
            new LambdaQueryWrapper<BotAiModelConfig>()
                .eq(BotAiModelConfig::getStatus, 1)
                .eq(BotAiModelConfig::getModelType, "Speech")
                .orderByDesc(BotAiModelConfig::getIsDefault)
                .orderByDesc(BotAiModelConfig::getCreateTime)
                .last("LIMIT 1"));
        if (configured != null && StringUtils.hasText(configured.getApiUrl())) {
            return config(configured.getApiUrl(), configured.getApiKey(), configured.getModelName(),
                configured.getProvider());
        }
        if (!fallbackEnabled) {
            throw new SpeechException(503,
                "语音转写尚未配置，请启用 Speech 类型模型或设置 SPEECH_ENABLED=true");
        }
        return config(fallbackApiUrl, fallbackApiKey, fallbackModel, "openai-compatible");
    }

    private SpeechConfig config(String apiUrl, String apiKey, String model, String provider) {
        String resolvedUrl = OpenAiSpeechTranscriptionClient.resolveTranscriptionUrl(apiUrl);
        if (!StringUtils.hasText(resolvedUrl)) {
            throw new SpeechException(503, "语音转写 API 地址未配置");
        }
        String resolvedApiKey = apiKey == null ? "" : apiKey.trim();
        if (StringUtils.hasText(resolvedApiKey)
                && !resolvedApiKey.matches("[A-Za-z0-9\\-._~+/]+=*")) {
            throw new SpeechException(503,
                "语音转写 API 密钥包含 Authorization 不支持的字符，请检查是否仍为占位符");
        }
        String resolvedModel = StringUtils.hasText(model) ? model.trim() : "whisper-1";
        String resolvedProvider = StringUtils.hasText(provider) ? provider.trim() : "openai-compatible";
        return new SpeechConfig(resolvedUrl, resolvedApiKey,
            resolvedModel, resolvedProvider, language == null ? "" : language.trim(),
            prompt == null ? "" : prompt.trim());
    }

    private void validateAudioHeader(Path path) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(path)) {
            header = input.readNBytes(16);
        }
        String extension = extension(path.getFileName().toString());
        boolean valid = switch (extension) {
            case "wav" -> startsWith(header, "RIFF") && containsAt(header, 8, "WAVE");
            case "mp3", "mpeg", "mpga" -> startsWith(header, "ID3") || isMpegFrame(header);
            case "mp4", "m4a" -> containsAt(header, 4, "ftyp");
            case "ogg" -> startsWith(header, "OggS");
            case "flac" -> startsWith(header, "fLaC");
            case "webm" -> startsWith(header,
                new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
            default -> false;
        };
        if (!valid) {
            throw new SpeechException(400, "音频内容损坏或格式与扩展名不一致");
        }
    }

    private boolean isMpegFrame(byte[] value) {
        return value.length >= 2
            && (value[0] & 0xFF) == 0xFF
            && ((value[1] & 0xE0) == 0xE0);
    }

    private boolean startsWith(byte[] value, String signature) {
        return startsWith(value, signature.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private boolean startsWith(byte[] value, byte[] signature) {
        if (value.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if (value[i] != signature[i]) return false;
        }
        return true;
    }

    private boolean containsAt(byte[] value, int offset, String signature) {
        byte[] expected = signature.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (value.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (value[offset + i] != expected[i]) return false;
        }
        return true;
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("[ \\t]+(?=\\n)", "")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    private String safeFileName(String value) {
        if (value == null || value.isBlank()) return "audio.webm";
        return Path.of(value).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String contentType(String extension) {
        return switch (extension) {
            case "flac" -> "audio/flac";
            case "mp3", "mpeg", "mpga" -> "audio/mpeg";
            case "mp4", "m4a" -> "audio/mp4";
            case "ogg" -> "audio/ogg";
            case "wav" -> "audio/wav";
            case "webm" -> "audio/webm";
            default -> "application/octet-stream";
        };
    }

    public record SpeechConfig(String apiUrl, String apiKey, String model, String provider,
                               String language, String prompt) {}

    public record TranscriptionResult(String text, String model, String provider,
                                      String language, long audioBytes, long durationMs) {}

    public record SpeechStatus(boolean available, String provider, String model,
                               long maxBytes, Set<String> formats, String error) {}

    public static class SpeechException extends RuntimeException {
        private final int status;

        public SpeechException(int status, String message) {
            super(message);
            this.status = status;
        }

        public SpeechException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
