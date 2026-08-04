package com.feisheng.bot.admin.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Service
public class ImageOcrService {
    private static final Set<String> SUPPORTED_EXTENSIONS =
        Set.of("png", "jpg", "jpeg", "bmp", "tif", "tiff");

    private final TesseractOcrEngine engine;
    private final long maxBytes;
    private final long maxPixels;
    private final int maxDimension;

    public ImageOcrService(
            TesseractOcrEngine engine,
            @Value("${ocr.image.max-bytes:10485760}") long maxBytes,
            @Value("${ocr.image.max-pixels:40000000}") long maxPixels,
            @Value("${ocr.image.max-dimension:3200}") int maxDimension) {
        this.engine = engine;
        this.maxBytes = maxBytes;
        this.maxPixels = maxPixels;
        this.maxDimension = maxDimension;
    }

    public boolean supports(String fileName) {
        return SUPPORTED_EXTENSIONS.contains(extension(fileName));
    }

    public void validateUploadSize(long size) {
        if (size <= 0 || size > maxBytes) {
            throw new TesseractOcrEngine.OcrException(
                "图片大小必须在 1 字节到 " + maxBytes + " 字节之间");
        }
    }

    public TesseractOcrEngine.EngineStatus status() {
        return engine.status();
    }

    public OcrResult extract(Path imagePath, String fileName) {
        if (!supports(fileName)) {
            throw new TesseractOcrEngine.OcrException(
                "不支持的图片类型，仅支持 png、jpg、jpeg、bmp、tif、tiff");
        }
        Path prepared = null;
        long started = System.currentTimeMillis();
        try {
            long size = Files.size(imagePath);
            validateUploadSize(size);

            BufferedImage source = ImageIO.read(imagePath.toFile());
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                throw new TesseractOcrEngine.OcrException("图片内容损坏或格式与扩展名不一致");
            }
            long pixels = (long) source.getWidth() * source.getHeight();
            if (pixels > maxPixels) {
                throw new TesseractOcrEngine.OcrException("图片像素数超过限制: " + pixels);
            }

            prepared = Files.createTempFile("feisheng-ocr-prepared-", ".png");
            BufferedImage normalized = normalize(source);
            if (!ImageIO.write(normalized, "png", prepared.toFile())) {
                throw new TesseractOcrEngine.OcrException("图片预处理失败");
            }

            String text = normalizeText(engine.recognize(prepared));
            if (text.isBlank()) {
                throw new TesseractOcrEngine.OcrException("图片中未识别到文字");
            }
            long meaningfulCharacters = text.codePoints()
                .filter(Character::isLetterOrDigit)
                .count();
            if (meaningfulCharacters < 2) {
                throw new TesseractOcrEngine.OcrException("图片 OCR 结果有效字符过少");
            }
            return new OcrResult(text, source.getWidth(), source.getHeight(),
                "tesseract", engine.languages(), System.currentTimeMillis() - started);
        } catch (TesseractOcrEngine.OcrException e) {
            throw e;
        } catch (IOException e) {
            throw new TesseractOcrEngine.OcrException("读取图片失败: " + e.getMessage(), e);
        } finally {
            if (prepared != null) {
                try {
                    Files.deleteIfExists(prepared);
                } catch (IOException ignored) {}
            }
        }
    }

    private BufferedImage normalize(BufferedImage source) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int longest = Math.max(sourceWidth, sourceHeight);
        double scale = longest > maxDimension
            ? (double) maxDimension / longest
            : longest < 1600 ? Math.min(2.0, (double) maxDimension / longest) : 1.0;
        int width = Math.max(1, (int) Math.round(sourceWidth * scale));
        int height = Math.max(1, (int) Math.round(sourceHeight * scale));

        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }

        BufferedImage grayscale = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D grayGraphics = grayscale.createGraphics();
        try {
            grayGraphics.drawImage(rgb, 0, 0, null);
        } finally {
            grayGraphics.dispose();
        }
        return grayscale;
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("[ \\t]+(?=\\n)", "")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    private String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record OcrResult(String text, int width, int height,
                            String engine, String language, long durationMs) {}
}
