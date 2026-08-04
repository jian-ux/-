package com.feisheng.bot.admin.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
