package com.feisheng.bot.admin.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse uploaded documents to extract plain text.
 * Supports: .txt, .md, .html, .docx, .pdf, .xlsx, .xls
 */
@Service
public class DocumentParseService {
    private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);
    private static final Pattern DOCX_HEADING_STYLE = Pattern.compile(
        "(?:heading|标题)\\s*([1-6])", Pattern.CASE_INSENSITIVE);

    public String parse(Path filePath, String fileName) throws Exception {
        String name = fileName.toLowerCase();
        if (name.endsWith(".txt") || name.endsWith(".md")) {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        }
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return parseHtml(Files.readString(filePath, StandardCharsets.UTF_8));
        }
        if (name.endsWith(".docx")) {
            return parseDocx(filePath);
        }
        if (name.endsWith(".pdf")) {
            return parsePdf(filePath);
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return parseExcel(filePath, name);
        }
        throw new UnsupportedOperationException("不支持的文件类型: " + name);
    }

    /** Parse .pdf with Apache PDFBox */
    private String parsePdf(Path filePath) throws Exception {
        try (var doc = Loader.loadPDF(filePath.toFile())) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder pages = new StringBuilder();
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = normalizeExtractedText(stripper.getText(doc));
                if (pageText.isBlank()) continue;
                if (doc.getNumberOfPages() > 1) {
                    pages.append("# 第 ").append(page).append(" 页\n");
                }
                pages.append(pageText).append("\n\n");
            }
            String text = pages.toString().trim();
            log.info("PDF parsed: {} chars", text.length());
            return text;
        }
    }

    /** Parse .xlsx/.xls with Apache POI */
    private String parseExcel(Path filePath, String fileName) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             Workbook wb = fileName.endsWith(".xlsx")
                 ? new XSSFWorkbook(fis) : new HSSFWorkbook(fis)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                sb.append("# 工作表：").append(sheet.getSheetName()).append("\n");
                for (Row row : sheet) {
                    List<String> values = new ArrayList<>();
                    for (Cell cell : row) {
                        String val = getCellValue(cell);
                        if (!val.isEmpty()) values.add(val);
                    }
                    if (!values.isEmpty()) sb.append(String.join("\t", values)).append("\n");
                }
                sb.append("\n");
            }
        }
        String text = sb.toString().trim();
        log.info("Excel parsed: {} chars", text.length());
        return text;
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell))
                    yield cell.getLocalDateTimeCellValue().toString();
                double v = cell.getNumericCellValue();
                yield v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue().trim(); }
                catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }

    /** Parse DOCX body elements in their original order and expose heading structure. */
    private String parseDocx(Path filePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             XWPFDocument doc = new XWPFDocument(fis)) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendDocxParagraph(sb, paragraph);
                } else if (element instanceof XWPFTable table) {
                    appendDocxTable(sb, table);
                }
            }
        }
        String text = sb.toString().replaceAll("\n{3,}", "\n\n").trim();
        log.info("DOCX parsed: {} chars", text.length());
        return text;
    }

    private void appendDocxParagraph(StringBuilder target, XWPFParagraph paragraph) {
        String text = paragraph.getText();
        if (text == null || text.isBlank()) return;
        int headingLevel = docxHeadingLevel(paragraph.getStyle());
        if (headingLevel > 0) {
            target.append("#".repeat(headingLevel)).append(' ');
        }
        target.append(text.trim()).append("\n\n");
    }

    private int docxHeadingLevel(String style) {
        if (style == null || style.isBlank()) return 0;
        Matcher matcher = DOCX_HEADING_STYLE.matcher(style.toLowerCase(Locale.ROOT));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private void appendDocxTable(StringBuilder target, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            List<String> values = row.getTableCells().stream()
                .map(cell -> cell.getText().trim())
                .toList();
            target.append(String.join("\t", values)).append('\n');
        }
        target.append('\n');
    }

    private String parseHtml(String html) {
        var document = Jsoup.parse(html == null ? "" : html);
        StringBuilder text = new StringBuilder();
        for (Element element : document.select("h1,h2,h3,h4,h5,h6,p,li,tr")) {
            if (isNestedSemanticElement(element)) continue;
            String value = "tr".equals(element.tagName())
                ? element.select("th,td").stream().map(Element::text)
                    .reduce((left, right) -> left + "\t" + right).orElse("")
                : element.text();
            if (value.isBlank()) continue;
            if (element.tagName().matches("h[1-6]")) {
                int level = Integer.parseInt(element.tagName().substring(1));
                text.append("#".repeat(level)).append(' ');
            }
            text.append(value.trim()).append("\n\n");
        }
        if (text.length() == 0 && document.body() != null) text.append(document.body().text());
        return text.toString().trim();
    }

    private boolean isNestedSemanticElement(Element element) {
        if ("tr".equals(element.tagName())) return false;
        for (Element parent : element.parents()) {
            if (parent.tagName().matches("p|li|td|th")) return true;
        }
        return false;
    }

    private String normalizeExtractedText(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace('\r', '\n')
            .replaceAll("[ \\t]+(?=\\n)", "")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }
}
