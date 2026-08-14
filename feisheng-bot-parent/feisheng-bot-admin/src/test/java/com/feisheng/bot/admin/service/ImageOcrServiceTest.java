package com.feisheng.bot.admin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageOcrServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void validatesPreprocessesAndExtractsText() throws Exception {
        TesseractOcrEngine engine = mock(TesseractOcrEngine.class);
        when(engine.recognize(any(Path.class))).thenReturn("账号：feisheng\n状态：正常\n");
        when(engine.languages()).thenReturn("chi_sim+eng");
        ImageOcrService service = new ImageOcrService(engine, 1024 * 1024, 1_000_000, 1600);
        Path image = createImage("screenshot.png", 320, 160);

        ImageOcrService.OcrResult result = service.extract(image, "screenshot.png");

        assertEquals("账号：feisheng\n状态：正常", result.text());
        assertEquals(320, result.width());
        assertEquals(160, result.height());
        assertEquals("chi_sim+eng", result.language());
        verify(engine).recognize(any(Path.class));
    }

    @Test
    void rejectsRenamedNonImageFile() throws Exception {
        TesseractOcrEngine engine = mock(TesseractOcrEngine.class);
        ImageOcrService service = new ImageOcrService(engine, 1024, 1_000_000, 1600);
        Path fake = tempDir.resolve("fake.png");
        Files.writeString(fake, "not an image");

        TesseractOcrEngine.OcrException error = assertThrows(
            TesseractOcrEngine.OcrException.class,
            () -> service.extract(fake, "fake.png"));

        assertTrue(error.getMessage().contains("内容损坏"));
    }

    @Test
    void rejectsEmptyOcrResult() throws Exception {
        TesseractOcrEngine engine = mock(TesseractOcrEngine.class);
        when(engine.recognize(any(Path.class))).thenReturn("  \n");
        ImageOcrService service = new ImageOcrService(engine, 1024 * 1024, 1_000_000, 1600);
        Path image = createImage("empty.png", 200, 100);

        TesseractOcrEngine.OcrException error = assertThrows(
            TesseractOcrEngine.OcrException.class,
            () -> service.extract(image, "empty.png"));

        assertTrue(error.getMessage().contains("未识别到文字"));
    }

    @Test
    void rejectsOcrNoiseWithTooFewMeaningfulCharacters() throws Exception {
        TesseractOcrEngine engine = mock(TesseractOcrEngine.class);
        when(engine.recognize(any(Path.class))).thenReturn("A ---");
        ImageOcrService service = new ImageOcrService(engine, 1024 * 1024, 1_000_000, 1600);
        Path image = createImage("noise.png", 200, 100);

        TesseractOcrEngine.OcrException error = assertThrows(
            TesseractOcrEngine.OcrException.class,
            () -> service.extract(image, "noise.png"));

        assertTrue(error.getMessage().contains("有效字符过少"));
    }

    @Test
    void supportsOnlyConfiguredImageExtensions() {
        ImageOcrService service = new ImageOcrService(mock(TesseractOcrEngine.class),
            1024, 1_000_000, 1600);
        assertTrue(service.supports("shot.PNG"));
        assertTrue(service.supports("scan.tiff"));
        assertEquals(false, service.supports("payload.svg"));
        assertEquals(false, service.supports("manual.pdf"));
    }

    @Test
    void recognizesGridTableCellByCellAndPreservesHeaderRelationships() throws Exception {
        TesseractOcrEngine engine = mock(TesseractOcrEngine.class);
        when(engine.recognize(any(Path.class)))
            .thenReturn("证件类型", "手机认证", "银行卡认证", "国际护照", "X", "V (数据宝)");
        when(engine.languages()).thenReturn("chi_sim+eng");
        ImageOcrService service = new ImageOcrService(engine,
            1024 * 1024, 1_000_000, 1600);
        Path image = createGridImage("table.png", 600, 300);

        ImageOcrService.OcrResult result = service.extract(image, "table.png");

        assertEquals("[结构化表格]\n"
                + "表头：证件类型；手机认证；银行卡认证\n"
                + "表格行：证件类型=国际护照；手机认证=×；银行卡认证=√ (数据宝)\n"
                + "[/结构化表格]", result.text());
        verify(engine, times(6)).recognize(any(Path.class));
    }

    @Test
    void rejectsGridTableWithDuplicateHeadersInsteadOfGuessingColumns() throws Exception {
        TesseractOcrEngine engine = mock(TesseractOcrEngine.class);
        when(engine.recognize(any(Path.class)))
            .thenReturn("证件类型", "认证", "认证", "国际护照", "X", "V");
        ImageOcrService service = new ImageOcrService(engine,
            1024 * 1024, 1_000_000, 1600);
        Path image = createGridImage("duplicate-header-table.png", 600, 300);

        TesseractOcrEngine.OcrException error = assertThrows(
            TesseractOcrEngine.OcrException.class,
            () -> service.extract(image, "duplicate-header-table.png"));

        assertTrue(error.getMessage().contains("表头重复"));
        verify(engine, times(6)).recognize(any(Path.class));
    }

    @Test
    void rejectsGridTableWhenAnyCellCannotBeRecognized() throws Exception {
        TesseractOcrEngine engine = mock(TesseractOcrEngine.class);
        when(engine.recognize(any(Path.class))).thenReturn("证件类型", "  \n", "  \n");
        ImageOcrService service = new ImageOcrService(engine,
            1024 * 1024, 1_000_000, 1600);
        Path image = createGridImage("incomplete-table.png", 600, 300);

        TesseractOcrEngine.OcrException error = assertThrows(
            TesseractOcrEngine.OcrException.class,
            () -> service.extract(image, "incomplete-table.png"));

        assertTrue(error.getMessage().contains("第 1 行第 2 列识别失败"));
        verify(engine, times(3)).recognize(any(Path.class));
    }

    private Path createImage(String name, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK);
            graphics.drawString("Feisheng OCR", 20, 50);
        } finally {
            graphics.dispose();
        }
        Path target = tempDir.resolve(name);
        ImageIO.write(image, "png", target.toFile());
        return target;
    }

    private Path createGridImage(String name, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK);
            graphics.setStroke(new BasicStroke(2));
            int left = 20;
            int right = width - 20;
            int top = 20;
            int middleY = height / 2;
            int bottom = height - 20;
            int firstThird = left + (right - left) / 3;
            int secondThird = left + (right - left) * 2 / 3;
            for (int x : new int[] {left, firstThird, secondThird, right}) {
                graphics.drawLine(x, top, x, bottom);
            }
            for (int y : new int[] {top, middleY, bottom}) {
                graphics.drawLine(left, y, right, y);
            }
        } finally {
            graphics.dispose();
        }
        Path target = tempDir.resolve(name);
        ImageIO.write(image, "png", target.toFile());
        return target;
    }
}
