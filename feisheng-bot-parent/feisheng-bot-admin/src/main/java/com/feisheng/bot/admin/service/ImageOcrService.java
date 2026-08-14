package com.feisheng.bot.admin.service;

import com.feisheng.bot.common.util.StructuredTableUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ImageOcrService {
    private static final int GRID_DARK_THRESHOLD = 96;
    private static final double GRID_LINE_COVERAGE = 0.65;
    private static final double GRID_BOUNDS_COVERAGE = 0.65;
    private static final int MIN_GRID_LINES = 3;
    private static final int MIN_CELL_SIZE = 12;
    private static final int MAX_GRID_CELLS = 400;
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

            GridTable gridTable = detectGridTable(normalized);
            String text = gridTable == null
                ? normalizeText(engine.recognize(prepared))
                : recognizeGridTable(normalized, gridTable);
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

    private GridTable detectGridTable(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] darkByColumn = new int[width];
        int[] darkByRow = new int[height];
        Raster raster = image.getRaster();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (raster.getSample(x, y, 0) < GRID_DARK_THRESHOLD) {
                    darkByColumn[x]++;
                    darkByRow[y]++;
                }
            }
        }

        List<GridLine> columns = findGridLines(darkByColumn, height);
        List<GridLine> rows = findGridLines(darkByRow, width);
        if (columns.size() < MIN_GRID_LINES || rows.size() < MIN_GRID_LINES) {
            return null;
        }

        int coveredWidth = columns.get(columns.size() - 1).end() - columns.get(0).start() + 1;
        int coveredHeight = rows.get(rows.size() - 1).end() - rows.get(0).start() + 1;
        if (coveredWidth < width * GRID_BOUNDS_COVERAGE
                || coveredHeight < height * GRID_BOUNDS_COVERAGE) {
            return null;
        }

        if (!hasUsableCells(columns) || !hasUsableCells(rows)) {
            return null;
        }
        return new GridTable(columns, rows);
    }

    private List<GridLine> findGridLines(int[] darkCounts, int crossAxisLength) {
        int requiredDarkPixels = (int) Math.ceil(crossAxisLength * GRID_LINE_COVERAGE);
        List<GridLine> lines = new ArrayList<>();
        int runStart = -1;
        for (int i = 0; i <= darkCounts.length; i++) {
            boolean isLine = i < darkCounts.length && darkCounts[i] >= requiredDarkPixels;
            if (isLine && runStart < 0) {
                runStart = i;
            } else if (!isLine && runStart >= 0) {
                lines.add(new GridLine(runStart, i - 1));
                runStart = -1;
            }
        }
        return lines;
    }

    private boolean hasUsableCells(List<GridLine> lines) {
        for (int i = 0; i < lines.size() - 1; i++) {
            int size = lines.get(i + 1).start() - lines.get(i).end() - 1;
            if (size < MIN_CELL_SIZE) {
                return false;
            }
        }
        return true;
    }

    private String recognizeGridTable(BufferedImage image, GridTable table) {
        int rowCount = table.rows().size() - 1;
        int columnCount = table.columns().size() - 1;
        if ((long) rowCount * columnCount > MAX_GRID_CELLS) {
            throw new TesseractOcrEngine.OcrException(
                "表格单元格过多，无法可靠识别: " + rowCount + "x" + columnCount);
        }

        List<List<String>> cells = new ArrayList<>(rowCount);
        for (int row = 0; row < rowCount; row++) {
            List<String> values = new ArrayList<>(columnCount);
            for (int column = 0; column < columnCount; column++) {
                values.add(recognizeCell(image, table, row, column));
            }
            cells.add(values);
        }
        try {
            // Persist header-to-cell relationships explicitly.  A later text
            // normaliser may collapse tabs, but it cannot detach these keys.
            return StructuredTableUtil.serialize(cells);
        } catch (IllegalArgumentException e) {
            throw new TesseractOcrEngine.OcrException(
                "表格结构未能可靠识别，已阻止导入: " + e.getMessage(), e);
        }
    }

    private String recognizeCell(BufferedImage image, GridTable table, int row, int column) {
        GridLine left = table.columns().get(column);
        GridLine right = table.columns().get(column + 1);
        GridLine top = table.rows().get(row);
        GridLine bottom = table.rows().get(row + 1);
        int x = left.end() + 1;
        int y = top.end() + 1;
        int width = right.start() - x;
        int height = bottom.start() - y;

        Path cellPath = null;
        try {
            cellPath = Files.createTempFile("feisheng-ocr-cell-", ".png");
            String text = "";
            for (int inset : new int[] {3, 0}) {
                BufferedImage cell = prepareCell(image, x, y, width, height, inset);
                if (!ImageIO.write(cell, "png", cellPath.toFile())) {
                    throw new TesseractOcrEngine.OcrException("表格单元格图片预处理失败");
                }
                text = normalizeCellText(engine.recognize(cellPath));
                if (!text.isBlank()) break;
            }
            if (text.isBlank()) {
                throw new TesseractOcrEngine.OcrException("未识别到文字");
            }
            return text;
        } catch (TesseractOcrEngine.OcrException e) {
            throw new TesseractOcrEngine.OcrException(
                "表格第 " + (row + 1) + " 行第 " + (column + 1) + " 列识别失败: "
                    + e.getMessage(), e);
        } catch (IOException e) {
            throw new TesseractOcrEngine.OcrException(
                "表格第 " + (row + 1) + " 行第 " + (column + 1) + " 列读取失败: "
                    + e.getMessage(), e);
        } finally {
            if (cellPath != null) {
                try {
                    Files.deleteIfExists(cellPath);
                } catch (IOException ignored) {}
            }
        }
    }

    private BufferedImage prepareCell(BufferedImage source, int x, int y, int width, int height,
                                      int inset) {
        int safeInset = Math.min(inset, Math.max(0, Math.min(width, height) / 4));
        int sourceX = x + safeInset;
        int sourceY = y + safeInset;
        int sourceWidth = width - safeInset * 2;
        int sourceHeight = height - safeInset * 2;
        int scale = sourceHeight < 80
            ? Math.min(3, (int) Math.ceil(80.0 / sourceHeight)) : 1;
        int padding = 12;
        BufferedImage cell = new BufferedImage(
            sourceWidth * scale + padding * 2,
            sourceHeight * scale + padding * 2,
            BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = cell.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, cell.getWidth(), cell.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source,
                padding, padding, padding + sourceWidth * scale, padding + sourceHeight * scale,
                sourceX, sourceY, sourceX + sourceWidth, sourceY + sourceHeight, null);
        } finally {
            graphics.dispose();
        }
        return cell;
    }

    private String normalizeCellText(String text) {
        String normalized = normalizeText(text).replaceAll("\\s+", " ").trim();
        // OCR often inserts spaces at Chinese line wraps.  Removing only
        // spaces between Han characters restores the cell label without
        // changing meaningful spaces in URLs, numbers, or Latin text.
        normalized = normalized.replaceAll("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})", "");
        if (normalized.equalsIgnoreCase("x") || normalized.equals("×")) {
            return "×";
        }
        if (normalized.equalsIgnoreCase("v")
                || normalized.equals("\\") || normalized.equals("＼") || normalized.equals("|")) {
            return "√";
        }
        String markOnly = normalized.replaceAll("[|\\\\vV√\\s]+", "");
        if (markOnly.isBlank() && normalized.matches(".*[|\\\\vV√].*")) {
            return "√";
        }
        int openingParenthesis = normalized.indexOf('(');
        if (openingParenthesis < 0) {
            openingParenthesis = normalized.indexOf('（');
        }
        if (openingParenthesis > 0) {
            String mark = normalized.substring(0, openingParenthesis).trim();
            if (mark.equalsIgnoreCase("v") || mark.equals("\\") || mark.equals("＼")
                    || mark.equals("|")) {
                return "√ " + normalized.substring(openingParenthesis);
            }
        }
        return normalized;
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

    private record GridLine(int start, int end) {}

    private record GridTable(List<GridLine> columns, List<GridLine> rows) {}

    public record OcrResult(String text, int width, int height,
                            String engine, String language, long durationMs) {}
}
