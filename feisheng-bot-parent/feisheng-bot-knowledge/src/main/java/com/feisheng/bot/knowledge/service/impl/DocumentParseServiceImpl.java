package com.feisheng.bot.knowledge.service.impl;
import com.feisheng.bot.knowledge.entity.BotKnowledgeItem;
import com.feisheng.bot.knowledge.mapper.BotKnowledgeItemMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
@Service
public class DocumentParseServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(DocumentParseServiceImpl.class);
    private final BotKnowledgeItemMapper itemMapper;
    public DocumentParseServiceImpl(BotKnowledgeItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }
    public int parseAndImport(MultipartFile file, Long categoryId) throws Exception {
        String fileName = file.getOriginalFilename();
        String ext = fileName != null ? fileName.toLowerCase() : "";

        if (ext.endsWith(".txt") || ext.endsWith(".csv") || ext.endsWith(".tsv")) {
            return parseTextFile(file, categoryId);
        } else if (ext.endsWith(".docx")) {
            return parseDocxFile(file, categoryId);
        } else if (ext.endsWith(".pdf")) {
            return parsePdfFile(file, categoryId);
        }
        throw new UnsupportedOperationException("Unsupported file type: " + ext);
    }
    private int parseTextFile(MultipartFile file, Long categoryId) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        List<BotKnowledgeItem> items = parseLines(lines, categoryId);
        return batchInsert(items, file.getOriginalFilename());
    }
    private int parseDocxFile(MultipartFile file, Long categoryId) throws Exception {
        List<String> lines = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText().trim();
                if (!text.isEmpty()) lines.add(text);
            }
        }
        List<BotKnowledgeItem> items = parseLines(lines, categoryId);
        return batchInsert(items, file.getOriginalFilename());
    }
    private int parsePdfFile(MultipartFile file, Long categoryId) throws Exception {
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            String[] rawLines = text.split("\\r?\\n");
            List<String> lines = new ArrayList<>();
            for (String line : rawLines) {
                String t = line.trim();
                if (!t.isEmpty()) lines.add(t);
            }
            List<BotKnowledgeItem> items = parseLines(lines, categoryId);
            return batchInsert(items, file.getOriginalFilename());
        }
    }
    private List<BotKnowledgeItem> parseLines(List<String> lines, Long categoryId) {
        List<BotKnowledgeItem> items = new ArrayList<>();
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            String[] parts;
            if (line.contains("\t")) parts = line.split("\t", 3);
            else parts = line.split(",", 3);
            if (parts.length >= 2) {
                BotKnowledgeItem item = new BotKnowledgeItem();
                item.setCategoryId(categoryId);
                item.setQuestion(parts[0].trim());
                item.setAnswer(parts[1].trim());
                if (parts.length >= 3) item.setKeywords(parts[2].trim());
                item.setStatus(1);
                items.add(item);
            }
        }
        return items;
    }
    private int batchInsert(List<BotKnowledgeItem> items, String fileName) {
        int count = 0;
        for (BotKnowledgeItem item : items) {
            itemMapper.insert(item);
            count++;
        }
        log.info("Imported {} knowledge items from {}", count, fileName);
        return count;
    }
}
