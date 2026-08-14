package com.feisheng.bot.admin.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse uploaded documents to extract plain text.
 * Supports: .txt, .md, .html, .docx, .pdf, .xlsx, .xls
 */
@Service
public class DocumentParseService {
    private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);
    private static final String EMBEDDED_IMAGE_FAILURE_MARKER =
        "[文档内嵌图片未成功识别，知识库导入已拦截]";
    private static final Pattern DOCX_HEADING_STYLE = Pattern.compile(
        "(?:heading|标题)\\s*([1-6])", Pattern.CASE_INSENSITIVE);

    /**
     * Kept for unit tests and non-Spring callers. Production wiring uses the
     * constructor below so DOCX images can share the existing OCR service.
     */
    private final ImageOcrService imageOcrService;

    public DocumentParseService() {
        this(null);
    }

    @Autowired
    public DocumentParseService(ImageOcrService imageOcrService) {
        this.imageOcrService = imageOcrService;
    }

    public String parse(Path filePath, String fileName) throws Exception {
        return parseDetailed(filePath, fileName).text();
    }

    public ParsedDocument parseDetailed(Path filePath, String fileName) throws Exception {
        String name = fileName.toLowerCase();
        if (name.endsWith(".txt") || name.endsWith(".md")) {
            return ParsedDocument.plain(Files.readString(filePath, StandardCharsets.UTF_8));
        }
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return ParsedDocument.plain(parseHtml(Files.readString(filePath, StandardCharsets.UTF_8)));
        }
        if (name.endsWith(".docx")) {
            return parseDocx(filePath);
        }
        if (name.endsWith(".pdf")) {
            return ParsedDocument.plain(parsePdf(filePath));
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
    private ParsedDocument parseExcel(Path filePath, String fileName) throws Exception {
        StringBuilder fullText = new StringBuilder();
        StringBuilder unstructuredText = new StringBuilder();
        List<QaRow> qaRows = new ArrayList<>();
        int sourceRowCount = 0;
        int invalidRowCount = 0;
        boolean structuredQaDetected = false;
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             Workbook wb = fileName.endsWith(".xlsx")
                 ? new XSSFWorkbook(fis) : new HSSFWorkbook(fis)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                QaHeader header = findQaHeader(sheet);
                if (header != null) {
                    structuredQaDetected = true;
                    fullText.append("# 工作表：").append(sheet.getSheetName()).append("\n\n");
                    for (int rowIndex = header.rowIndex() + 1;
                         rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                        Row row = sheet.getRow(rowIndex);
                        if (row == null || isEmptyRow(row)) continue;
                        sourceRowCount++;
                        String question = getCellValue(row.getCell(header.questionColumn()));
                        String answer = getCellValue(row.getCell(header.answerColumn()));
                        if (question.isBlank() || answer.isBlank()) {
                            invalidRowCount++;
                            continue;
                        }
                        QaRow qaRow = new QaRow(question, answer,
                            "工作表：" + sheet.getSheetName(), rowIndex + 1);
                        qaRows.add(qaRow);
                        appendQaDisplay(fullText, qaRow);
                    }
                    continue;
                }

                appendSheetAsPlainText(fullText, sheet);
                appendSheetAsPlainText(unstructuredText, sheet);
            }
        }
        String text = fullText.toString().trim();
        String remaining = unstructuredText.toString().trim();
        log.info("Excel parsed: {} chars, {} structured Q&A rows, {} invalid rows",
            text.length(), qaRows.size(), invalidRowCount);
        return new ParsedDocument(text, remaining, qaRows,
            new ParseDiagnostics(structuredQaDetected, sourceRowCount,
                qaRows.size(), invalidRowCount));
    }

    private void appendSheetAsPlainText(StringBuilder target, Sheet sheet) {
        target.append("# 工作表：").append(sheet.getSheetName()).append("\n");
        for (Row row : sheet) {
            List<String> values = rowValues(row);
            if (!values.isEmpty()) target.append(String.join("\t", values)).append("\n");
        }
        target.append("\n");
    }

    private QaHeader findQaHeader(Sheet sheet) {
        int inspected = 0;
        for (Row row : sheet) {
            if (isEmptyRow(row)) continue;
            inspected++;
            int questionColumn = -1;
            int answerColumn = -1;
            short lastCell = row.getLastCellNum();
            for (int column = 0; column < lastCell; column++) {
                String header = normalizeHeader(getCellValue(row.getCell(column)));
                if (isQuestionHeader(header)) questionColumn = column;
                if (isAnswerHeader(header)) answerColumn = column;
            }
            if (questionColumn >= 0 && answerColumn >= 0 && questionColumn != answerColumn) {
                return new QaHeader(row.getRowNum(), questionColumn, answerColumn);
            }
            if (inspected >= 20) break;
        }
        return null;
    }

    private boolean isEmptyRow(Row row) {
        if (row == null || row.getLastCellNum() < 0) return true;
        for (int column = 0; column < row.getLastCellNum(); column++) {
            if (!getCellValue(row.getCell(column)).isBlank()) return false;
        }
        return true;
    }

    private List<String> rowValues(Row row) {
        if (row == null || row.getLastCellNum() < 0) return List.of();
        List<String> values = new ArrayList<>();
        for (int column = 0; column < row.getLastCellNum(); column++) {
            values.add(getCellValue(row.getCell(column)));
        }
        while (!values.isEmpty() && values.get(values.size() - 1).isBlank()) {
            values.remove(values.size() - 1);
        }
        return values.stream().anyMatch(value -> !value.isBlank()) ? values : List.of();
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
    private ParsedDocument parseDocx(Path filePath) throws Exception {
        StringBuilder fullText = new StringBuilder();
        StringBuilder unstructuredText = new StringBuilder();
        List<QaRow> qaRows = new ArrayList<>();
        DocxImageDiagnostics imageDiagnostics = new DocxImageDiagnostics();
        int sourceRowCount = 0;
        int invalidRowCount = 0;
        boolean structuredQaDetected = false;
        int tableNumber = 0;
        int paragraphNumber = 0;
        List<DocxParagraphEntry> paragraphBlock = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(filePath.toFile());
             XWPFDocument doc = new XWPFDocument(fis)) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    paragraphNumber++;
                    String paragraphText = extractDocxParagraphText(paragraph, imageDiagnostics);
                    paragraphBlock.add(new DocxParagraphEntry(
                        paragraph, paragraphText, paragraphNumber));
                } else if (element instanceof XWPFTable table) {
                    DocxParagraphStats paragraphStats = appendDocxParagraphBlock(
                        paragraphBlock, fullText, unstructuredText, qaRows);
                    paragraphBlock.clear();
                    sourceRowCount += paragraphStats.sourceRowCount();
                    invalidRowCount += paragraphStats.invalidRowCount();
                    structuredQaDetected |= paragraphStats.sourceRowCount() > 0;
                    tableNumber++;
                    DocxQaTable parsedTable = parseDocxQaTable(table, tableNumber, imageDiagnostics);
                    if (parsedTable != null) {
                        structuredQaDetected = true;
                        sourceRowCount += parsedTable.sourceRowCount();
                        invalidRowCount += parsedTable.invalidRowCount();
                        qaRows.addAll(parsedTable.qaRows());
                        fullText.append("## 表格 ").append(tableNumber).append("\n\n");
                        parsedTable.qaRows().forEach(row -> appendQaDisplay(fullText, row));
                    } else {
                        String tableText = extractDocxTableText(table, imageDiagnostics);
                        fullText.append(tableText);
                        unstructuredText.append(tableText);
                    }
                }
            }
            DocxParagraphStats paragraphStats = appendDocxParagraphBlock(
                paragraphBlock, fullText, unstructuredText, qaRows);
            sourceRowCount += paragraphStats.sourceRowCount();
            invalidRowCount += paragraphStats.invalidRowCount();
            structuredQaDetected |= paragraphStats.sourceRowCount() > 0;
        }
        String text = normalizeDocumentSpacing(fullText);
        String remaining = normalizeDocumentSpacing(unstructuredText);
        log.info("DOCX parsed: {} chars, {} structured Q&A rows, {} invalid rows, "
                + "{} embedded images ({} unavailable)",
            text.length(), qaRows.size(), invalidRowCount,
            imageDiagnostics.embeddedImageCount, imageDiagnostics.unprocessedImageCount);
        return new ParsedDocument(text, remaining, qaRows,
            new ParseDiagnostics(structuredQaDetected, sourceRowCount,
                qaRows.size(), invalidRowCount,
                imageDiagnostics.embeddedImageCount,
                imageDiagnostics.unprocessedImageCount));
    }

    private DocxQaTable parseDocxQaTable(XWPFTable table, int tableNumber,
                                         DocxImageDiagnostics imageDiagnostics) {
        List<XWPFTableRow> rows = table.getRows();
        for (int headerIndex = 0; headerIndex < Math.min(rows.size(), 20); headerIndex++) {
            List<String> headerValues = rows.get(headerIndex).getTableCells().stream()
                .map(cell -> normalizeHeader(extractDocxCellText(cell, imageDiagnostics)))
                .toList();
            int questionColumn = findHeaderColumn(headerValues, true);
            int answerColumn = findHeaderColumn(headerValues, false);
            if (questionColumn < 0 || answerColumn < 0 || questionColumn == answerColumn) continue;

            List<QaRow> qaRows = new ArrayList<>();
            int sourceRows = 0;
            int invalidRows = 0;
            for (int rowIndex = headerIndex + 1; rowIndex < rows.size(); rowIndex++) {
                List<String> values = rows.get(rowIndex).getTableCells().stream()
                    .map(cell -> extractDocxCellText(cell, imageDiagnostics))
                    .toList();
                if (values.stream().allMatch(String::isBlank)) continue;
                sourceRows++;
                String question = valueAt(values, questionColumn);
                String answer = valueAt(values, answerColumn);
                if (question.isBlank() || answer.isBlank()) {
                    invalidRows++;
                    continue;
                }
                qaRows.add(new QaRow(question, answer,
                    "Word 表格 " + tableNumber, rowIndex + 1));
            }
            return new DocxQaTable(qaRows, sourceRows, invalidRows);
        }
        return null;
    }

    private DocxParagraphStats appendDocxParagraphBlock(
            List<DocxParagraphEntry> paragraphs, StringBuilder fullText,
            StringBuilder unstructuredText, List<QaRow> qaRows) {
        int sourceRows = 0;
        int invalidRows = 0;
        int index = 0;
        while (index < paragraphs.size()) {
            DocxParagraphEntry current = paragraphs.get(index);
            String question = QaBoundaryDetector.questionText(current.text());
            if (question == null || isDocxStructuralParagraph(current)) {
                appendDocxParagraph(fullText, current.paragraph(), current.text());
                appendDocxParagraph(unstructuredText, current.paragraph(), current.text());
                index++;
                continue;
            }

            boolean explicitQuestion = QaBoundaryDetector.hasExplicitQuestionLabel(current.text());
            List<String> answerLines = new ArrayList<>();
            int next = index + 1;
            while (next < paragraphs.size()) {
                DocxParagraphEntry candidate = paragraphs.get(next);
                if (candidate.text() == null || candidate.text().isBlank()
                        || isDocxStructuralParagraph(candidate)
                        || QaBoundaryDetector.questionText(candidate.text()) != null) {
                    break;
                }
                String answerLine = QaBoundaryDetector.answerText(candidate.text());
                if (!answerLine.isBlank()) answerLines.add(answerLine);
                next++;
            }

            if (!answerLines.isEmpty()) {
                sourceRows++;
                QaRow row = new QaRow(question, String.join("\n", answerLines),
                    "Word 段落", current.paragraphNumber());
                qaRows.add(row);
                appendQaDisplay(fullText, row);
                index = next;
                continue;
            }

            if (explicitQuestion) {
                sourceRows++;
                invalidRows++;
            }
            appendDocxParagraph(fullText, current.paragraph(), current.text());
            appendDocxParagraph(unstructuredText, current.paragraph(), current.text());
            index++;
        }
        return new DocxParagraphStats(sourceRows, invalidRows);
    }

    private boolean isDocxStructuralParagraph(DocxParagraphEntry entry) {
        if (entry == null || entry.paragraph() == null) return false;
        String style = entry.paragraph().getStyle();
        if (docxHeadingLevel(style) > 0) return true;
        return style != null && style.toLowerCase(Locale.ROOT).startsWith("toc");
    }

    private int findHeaderColumn(List<String> values, boolean question) {
        for (int index = 0; index < values.size(); index++) {
            if (question ? isQuestionHeader(values.get(index)) : isAnswerHeader(values.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private String valueAt(List<String> values, int index) {
        return index >= 0 && index < values.size() ? values.get(index).trim() : "";
    }

    private String normalizeHeader(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[\\s:：()（）【】\\[\\]_-]+", "");
    }

    private boolean isQuestionHeader(String value) {
        return switch (value) {
            case "q", "question", "问题", "提问", "问法", "标准问题", "客户问题", "用户问题" -> true;
            default -> false;
        };
    }

    private boolean isAnswerHeader(String value) {
        return switch (value) {
            case "a", "answer", "答案", "回答", "答复", "回复", "标准答案", "客服回答" -> true;
            default -> false;
        };
    }

    private void appendQaDisplay(StringBuilder target, QaRow row) {
        target.append("问题：").append(row.question()).append('\n')
            .append("答案：").append(row.answer()).append("\n\n");
    }

    private String normalizeDocumentSpacing(StringBuilder text) {
        return text.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    private void appendDocxParagraph(StringBuilder target, XWPFParagraph paragraph,
                                     String text) {
        if (text == null || text.isBlank()) return;
        int headingLevel = docxHeadingLevel(paragraph.getStyle());
        if (headingLevel > 0) {
            target.append("#".repeat(headingLevel)).append(' ');
        }
        target.append(text.trim()).append("\n\n");
    }

    /**
     * Extract run text and embedded pictures in run order. XWPFParagraph#getText()
     * silently drops pictures, which used to leave answers ending in a colon and
     * allowed the model to fill the missing table from an unrelated answer.
     */
    private String extractDocxParagraphText(XWPFParagraph paragraph,
                                            DocxImageDiagnostics imageDiagnostics) {
        if (paragraph == null) return "";
        StringBuilder text = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            String runText = run.text();
            if (runText != null && !runText.isEmpty()) text.append(runText);
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                String ocrText = extractEmbeddedPicture(picture.getPictureData(), imageDiagnostics);
                if (!ocrText.isBlank()) {
                    if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
                        text.append('\n');
                    }
                    text.append(ocrText).append('\n');
                }
            }
        }
        // Some documents expose text through a non-standard run implementation.
        // Preserve it as a fallback while still accounting for embedded images.
        if (text.length() == 0 && paragraph.getText() != null) {
            text.append(paragraph.getText());
        }
        return text.toString();
    }

    private int docxHeadingLevel(String style) {
        if (style == null || style.isBlank()) return 0;
        Matcher matcher = DOCX_HEADING_STYLE.matcher(style.toLowerCase(Locale.ROOT));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private String extractDocxTableText(XWPFTable table,
                                        DocxImageDiagnostics imageDiagnostics) {
        StringBuilder text = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            List<String> values = row.getTableCells().stream()
                .map(cell -> extractDocxCellText(cell, imageDiagnostics))
                .toList();
            text.append(String.join("\t", values)).append('\n');
        }
        text.append('\n');
        return text.toString();
    }

    private String extractDocxCellText(XWPFTableCell cell,
                                       DocxImageDiagnostics imageDiagnostics) {
        if (cell == null) return "";
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            String paragraphText = extractDocxParagraphText(paragraph, imageDiagnostics).trim();
            if (paragraphText.isBlank()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(paragraphText);
        }
        // Keep text from unusual OOXML constructs (for example a field run)
        // instead of silently dropping it when no regular paragraph was exposed.
        if (text.length() == 0 && cell.getText() != null) text.append(cell.getText());
        return text.toString().trim();
    }

    /** Extract and OCR one embedded DOCX picture without altering the source file. */
    private String extractEmbeddedPicture(XWPFPictureData pictureData,
                                          DocxImageDiagnostics imageDiagnostics) {
        if (pictureData != null && imageDiagnostics.ocrByPicture.containsKey(pictureData)) {
            return imageDiagnostics.ocrByPicture.get(pictureData);
        }
        imageDiagnostics.embeddedImageCount++;
        String extension = pictureData == null ? "png" : pictureData.suggestFileExtension();
        if (extension == null || extension.isBlank()) extension = "png";
        extension = extension.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (extension.isBlank()) extension = "png";
        String originalName = pictureData == null ? "embedded." + extension : pictureData.getFileName();
        if (originalName == null || originalName.isBlank()) originalName = "embedded." + extension;
        if (!originalName.contains(".")) originalName = originalName + "." + extension;

        Path extracted = null;
        try {
            if (pictureData == null || pictureData.getData() == null
                    || pictureData.getData().length == 0) {
                throw new IOException("empty embedded picture");
            }
            extracted = Files.createTempFile("feisheng-docx-image-", "." + extension);
            Files.write(extracted, pictureData.getData());
            if (imageOcrService == null) {
                throw new TesseractOcrEngine.OcrException("DOCX 内嵌图片 OCR 服务未配置");
            }
            ImageOcrService.OcrResult result = imageOcrService.extract(extracted, originalName);
            String text = result == null ? "" : result.text();
            if (text == null || text.isBlank()) {
                throw new TesseractOcrEngine.OcrException("DOCX 内嵌图片 OCR 未识别到文字");
            }
            String extractedText = "[文档内嵌图片 OCR]\n" + text.trim();
            if (pictureData != null) imageDiagnostics.ocrByPicture.put(pictureData, extractedText);
            return extractedText;
        } catch (Exception e) {
            imageDiagnostics.unprocessedImageCount++;
            log.warn("Could not OCR embedded DOCX image {}: {}", originalName, e.getMessage());
            if (pictureData != null) imageDiagnostics.ocrByPicture.put(
                pictureData, EMBEDDED_IMAGE_FAILURE_MARKER);
            return EMBEDDED_IMAGE_FAILURE_MARKER;
        } finally {
            if (extracted != null) {
                try {
                    Files.deleteIfExists(extracted);
                } catch (IOException ignored) {
                    log.debug("Could not delete extracted DOCX image {}", extracted);
                }
            }
        }
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

    public record QaRow(String question, String answer, String source, int sourceRowNumber) {}

    public record ParseDiagnostics(boolean structuredQaDetected, int sourceRowCount,
                                   int validQaRowCount, int invalidRowCount,
                                   int embeddedImageCount, int unprocessedImageCount) {
        /** Backward-compatible constructor for callers that do not parse DOCX images. */
        public ParseDiagnostics(boolean structuredQaDetected, int sourceRowCount,
                                int validQaRowCount, int invalidRowCount) {
            this(structuredQaDetected, sourceRowCount, validQaRowCount, invalidRowCount, 0, 0);
        }

        public ParseDiagnostics {
            sourceRowCount = Math.max(0, sourceRowCount);
            validQaRowCount = Math.max(0, validQaRowCount);
            invalidRowCount = Math.max(0, invalidRowCount);
            embeddedImageCount = Math.max(0, embeddedImageCount);
            unprocessedImageCount = Math.max(0, unprocessedImageCount);
        }
    }

    public record ParsedDocument(String text, String unstructuredText, List<QaRow> qaRows,
                                 ParseDiagnostics diagnostics) {
        public ParsedDocument {
            text = text == null ? "" : text;
            unstructuredText = unstructuredText == null ? "" : unstructuredText;
            qaRows = qaRows == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(qaRows));
            diagnostics = diagnostics == null
                ? new ParseDiagnostics(false, 0, 0, 0) : diagnostics;
        }

        private static ParsedDocument plain(String text) {
            String value = text == null ? "" : text;
            return new ParsedDocument(value, value, List.of(),
                new ParseDiagnostics(false, 0, 0, 0));
        }
    }

    private record QaHeader(int rowIndex, int questionColumn, int answerColumn) {}
    private record DocxQaTable(List<QaRow> qaRows, int sourceRowCount, int invalidRowCount) {}
    private record DocxParagraphEntry(XWPFParagraph paragraph, String text,
                                      int paragraphNumber) {}
    private record DocxParagraphStats(int sourceRowCount, int invalidRowCount) {}

    private static final class DocxImageDiagnostics {
        private int embeddedImageCount;
        private int unprocessedImageCount;
        private final Map<XWPFPictureData, String> ocrByPicture = new IdentityHashMap<>();
    }
}
