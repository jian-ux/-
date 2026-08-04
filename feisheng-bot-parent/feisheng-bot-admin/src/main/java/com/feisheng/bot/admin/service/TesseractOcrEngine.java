package com.feisheng.bot.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class TesseractOcrEngine {
    private static final Logger log = LoggerFactory.getLogger(TesseractOcrEngine.class);

    private final String command;
    private final String languages;
    private final long timeoutSeconds;

    public TesseractOcrEngine(
            @Value("${ocr.tesseract.command:tesseract}") String command,
            @Value("${ocr.tesseract.languages:chi_sim+eng}") String languages,
            @Value("${ocr.tesseract.timeout-seconds:60}") long timeoutSeconds) {
        this.command = command;
        this.languages = languages;
        this.timeoutSeconds = timeoutSeconds;
    }

    public EngineStatus status() {
        try {
            ProcessResult result = run(List.of(command, "--version"), 10);
            String firstLine = result.stdout().lines().findFirst().orElse("tesseract");
            return new EngineStatus(result.exitCode() == 0, firstLine, languages, null);
        } catch (Exception e) {
            return new EngineStatus(false, null, languages, e.getMessage());
        }
    }

    public String languages() {
        return languages;
    }

    public String recognize(Path imagePath) {
        if (imagePath == null || !Files.isRegularFile(imagePath)) {
            throw new OcrException("OCR 输入图片不存在");
        }
        if (languages == null || !languages.matches("[A-Za-z0-9_+.-]+")) {
            throw new OcrException("OCR_LANGUAGES 配置不合法");
        }

        List<String> args = new ArrayList<>();
        args.add(command);
        args.add(imagePath.toAbsolutePath().toString());
        args.add("stdout");
        args.add("-l");
        args.add(languages);
        args.add("--psm");
        args.add("6");

        ProcessResult result = run(args, timeoutSeconds);
        if (result.exitCode() != 0) {
            throw new OcrException("Tesseract 执行失败: " + truncate(result.stderr(), 500));
        }
        return result.stdout();
    }

    private ProcessResult run(List<String> args, long timeout) {
        Path stdout = null;
        Path stderr = null;
        Process process = null;
        try {
            stdout = Files.createTempFile("feisheng-tesseract-", ".out");
            stderr = Files.createTempFile("feisheng-tesseract-", ".err");
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.redirectOutput(stdout.toFile());
            builder.redirectError(stderr.toFile());
            process = builder.start();
            if (!process.waitFor(Math.max(1, timeout), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new OcrException("Tesseract 执行超时（" + timeout + " 秒）");
            }
            return new ProcessResult(
                process.exitValue(),
                Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
        } catch (OcrException e) {
            throw e;
        } catch (IOException e) {
            throw new OcrException("无法启动 Tesseract，请检查 OCR_TESSERACT_COMMAND: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new OcrException("Tesseract 执行被中断", e);
        } finally {
            deleteQuietly(stdout);
            deleteQuietly(stderr);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Could not delete OCR process temp file {}", path);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= maxLength
            ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}

    public record EngineStatus(boolean available, String version, String languages, String error) {}

    public static class OcrException extends RuntimeException {
        public OcrException(String message) {
            super(message);
        }

        public OcrException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
