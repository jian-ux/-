package com.feisheng.bot.admin.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentParseServiceTest {
    @TempDir
    Path tempDir;

    private final DocumentParseService service = new DocumentParseService();

    @Test
    void preservesHtmlHeadingsParagraphsAndTableRows() throws Exception {
        Path file = tempDir.resolve("manual.html");
        Files.writeString(file, """
            <html><body><h1>员工手册</h1><p>欢迎入职。</p>
            <table><tr><th>类型</th><th>天数</th></tr><tr><td>年假</td><td>5</td></tr></table>
            </body></html>
            """);

        String text = service.parse(file, file.getFileName().toString());

        assertTrue(text.contains("# 员工手册"));
        assertTrue(text.contains("欢迎入职。"));
        assertTrue(text.contains("类型\t天数"));
        assertTrue(text.contains("年假\t5"));
    }

    @Test
    void preservesDocxParagraphAndTableOrder() throws Exception {
        Path file = tempDir.resolve("manual.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("员工手册");
            document.createParagraph().createRun().setText("表格之前");
            XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("年假");
            table.getRow(0).getCell(1).setText("5天");
            document.createParagraph().createRun().setText("表格之后");
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        String text = service.parse(file, file.getFileName().toString());

        assertTrue(text.contains("# 员工手册"));
        assertTrue(text.indexOf("表格之前") < text.indexOf("年假\t5天"));
        assertTrue(text.indexOf("年假\t5天") < text.indexOf("表格之后"));
    }

    @Test
    void exposesExcelSheetAndRowStructure() throws Exception {
        Path file = tempDir.resolve("rules.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("请假规则");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("类型");
            header.createCell(1).setCellValue("天数");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("年假");
            data.createCell(1).setCellValue(5);
            try (var output = Files.newOutputStream(file)) {
                workbook.write(output);
            }
        }

        String text = service.parse(file, file.getFileName().toString());

        assertTrue(text.startsWith("# 工作表：请假规则"));
        assertTrue(text.contains("类型\t天数"));
        assertTrue(text.contains("年假\t5"));
    }

    @Test
    void extractsEachExcelQuestionAnswerRowAsStructuredData() throws Exception {
        Path file = tempDir.resolve("faq.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("客服问答");
            var title = sheet.createRow(0);
            title.createCell(0).setCellValue("点签客服知识库");
            var header = sheet.createRow(1);
            header.createCell(0).setCellValue("序号");
            header.createCell(1).setCellValue("提问");
            header.createCell(2).setCellValue("回答");
            var first = sheet.createRow(2);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue("支持哪些终端？");
            first.createCell(2).setCellValue("支持微信。\n也支持电脑端。");
            var second = sheet.createRow(3);
            second.createCell(0).setCellValue(2);
            second.createCell(1).setCellValue("如何开票？");
            second.createCell(2).setCellValue("请联系客户经理。");
            try (var output = Files.newOutputStream(file)) {
                workbook.write(output);
            }
        }

        DocumentParseService.ParsedDocument parsed =
            service.parseDetailed(file, file.getFileName().toString());

        assertTrue(parsed.diagnostics().structuredQaDetected());
        assertEquals(2, parsed.diagnostics().sourceRowCount());
        assertEquals(2, parsed.diagnostics().validQaRowCount());
        assertEquals(0, parsed.diagnostics().invalidRowCount());
        assertEquals(2, parsed.qaRows().size());
        assertEquals("支持微信。\n也支持电脑端。", parsed.qaRows().get(0).answer());
        assertTrue(parsed.unstructuredText().isBlank());
        assertTrue(parsed.text().contains("问题：支持哪些终端？"));
    }

    @Test
    void extractsRecognizedDocxQaTableButKeepsOtherTablesAsPlainText() throws Exception {
        Path file = tempDir.resolve("faq.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFTable qa = document.createTable(2, 2);
            qa.getRow(0).getCell(0).setText("问题");
            qa.getRow(0).getCell(1).setText("答案");
            qa.getRow(1).getCell(0).setText("合同有效吗？");
            qa.getRow(1).getCell(1).setText("依法签署后有效。\n可在线验证。");
            XWPFTable ordinary = document.createTable(2, 2);
            ordinary.getRow(0).getCell(0).setText("套餐");
            ordinary.getRow(0).getCell(1).setText("份数");
            ordinary.getRow(1).getCell(0).setText("基础版");
            ordinary.getRow(1).getCell(1).setText("100");
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        DocumentParseService.ParsedDocument parsed =
            service.parseDetailed(file, file.getFileName().toString());

        assertEquals(1, parsed.qaRows().size());
        assertEquals("合同有效吗？", parsed.qaRows().get(0).question());
        assertTrue(parsed.unstructuredText().contains("套餐\t份数"));
        assertTrue(parsed.unstructuredText().contains("基础版\t100"));
        assertFalse(parsed.unstructuredText().contains("合同有效吗？"));
    }

    @Test
    void extractsUnlabeledDocxParagraphQaWithStableBoundaries() throws Exception {
        Path file = tempDir.resolve("paragraph-faq.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("合同发起");
            document.createParagraph().createRun()
                .setText("批量发起合同支持多少份同时操作？");
            document.createParagraph().createRun().setText("支持同时发起10份");
            document.createParagraph().createRun().setText("合同发起后能撤回吗？");
            document.createParagraph().createRun().setText("未签署时可以撤回。");
            document.createParagraph().createRun()
                .setText("是否收费？这句话属于上一题答案内容。");
            document.createParagraph();
            document.createParagraph().createRun().setText("以下为普通补充说明");
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        DocumentParseService.ParsedDocument parsed =
            service.parseDetailed(file, file.getFileName().toString());

        assertTrue(parsed.diagnostics().structuredQaDetected());
        assertEquals(2, parsed.diagnostics().sourceRowCount());
        assertEquals(2, parsed.diagnostics().validQaRowCount());
        assertEquals(0, parsed.diagnostics().invalidRowCount());
        assertEquals("批量发起合同支持多少份同时操作？",
            parsed.qaRows().get(0).question());
        assertEquals("支持同时发起10份", parsed.qaRows().get(0).answer());
        assertTrue(parsed.qaRows().get(1).answer().contains("属于上一题答案内容"));
        assertFalse(parsed.unstructuredText().contains("批量发起合同"));
        assertTrue(parsed.unstructuredText().contains("合同发起"));
        assertTrue(parsed.unstructuredText().contains("普通补充说明"));
    }

    @Test
    void extractsEmbeddedPictureTextAndKeepsItAdjacentToPrecedingParagraph() throws Exception {
        ImageOcrService ocr = Mockito.mock(ImageOcrService.class);
        when(ocr.extract(any(Path.class), anyString())).thenReturn(
            new ImageOcrService.OcrResult(
                "外国人永久居留证：支持人脸、银行卡、人工审核",
                800, 400, "tesseract", "chi_sim+eng", 12));
        DocumentParseService parser = new DocumentParseService(ocr);
        Path file = tempDir.resolve("embedded-image.docx");

        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("外国友人仅支持以下方式认证：");
            var paragraph = document.createParagraph();
            paragraph.createRun().addPicture(new ByteArrayInputStream(pngBytes()),
                Document.PICTURE_TYPE_PNG, "auth-table.png",
                Units.toEMU(300), Units.toEMU(150));
            document.createParagraph().createRun().setText("下一条问题");
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        DocumentParseService.ParsedDocument parsed =
            parser.parseDetailed(file, file.getFileName().toString());

        String text = parsed.text();
        assertTrue(text.contains("外国人永久居留证：支持人脸、银行卡、人工审核"));
        assertTrue(text.indexOf("外国友人仅支持以下方式认证")
            < text.indexOf("外国人永久居留证"));
        assertTrue(text.indexOf("外国人永久居留证") < text.indexOf("下一条问题"));
        assertEquals(1, parsed.diagnostics().embeddedImageCount());
        assertEquals(0, parsed.diagnostics().unprocessedImageCount());
        verify(ocr, times(1)).extract(any(Path.class), anyString());
    }

    @Test
    void recordsAndMarksEmbeddedPictureWhenOcrFails() throws Exception {
        ImageOcrService ocr = Mockito.mock(ImageOcrService.class);
        when(ocr.extract(any(Path.class), anyString())).thenThrow(
            new TesseractOcrEngine.OcrException("ocr unavailable"));
        DocumentParseService parser = new DocumentParseService(ocr);
        Path file = tempDir.resolve("embedded-image-failure.docx");

        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("认证方式：");
            document.createParagraph().createRun().addPicture(
                new ByteArrayInputStream(pngBytes()), Document.PICTURE_TYPE_PNG,
                "auth-table.png", Units.toEMU(300), Units.toEMU(150));
            try (var output = Files.newOutputStream(file)) {
                document.write(output);
            }
        }

        DocumentParseService.ParsedDocument parsed =
            parser.parseDetailed(file, file.getFileName().toString());

        assertEquals(1, parsed.diagnostics().embeddedImageCount());
        assertEquals(1, parsed.diagnostics().unprocessedImageCount());
        assertTrue(parsed.text().contains("文档内嵌图片未成功识别"));
        verify(ocr, times(1)).extract(any(Path.class), anyString());
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
