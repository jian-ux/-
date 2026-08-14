package com.feisheng.bot.admin.service;

import com.feisheng.bot.common.util.KnowledgeTextUtil;
import com.feisheng.bot.common.util.StructuredQaUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Chinese-friendly, structure-aware text chunking. */
@Service
public class ChunkingService {
    public static final String STRATEGY_VERSION = "chunk-v7";
    private static final int DEFAULT_MAX_CHARS = 600;
    private static final int DEFAULT_MIN_CHARS = 50;
    private static final int DEFAULT_OVERLAP = 80;

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);
    @Value("${knowledge.chunking.max-chars:600}")
    private int defaultMaxChars = DEFAULT_MAX_CHARS;
    @Value("${knowledge.chunking.min-chars:50}")
    private int defaultMinChars = DEFAULT_MIN_CHARS;
    @Value("${knowledge.chunking.overlap-chars:80}")
    private int defaultOverlap = DEFAULT_OVERLAP;
    private static final Pattern SENTENCE_BOUNDARY_RE = Pattern.compile("(?<=[。！？!?；;])|\\n+");
    private static final Pattern MARKDOWN_HEADER_RE = Pattern.compile(
        "^\\s*(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern HEADER_RE = Pattern.compile(
        "^[一二三四五六七八九十百千万]+[、.．]|" +
        "^[（(][一二三四五六七八九十]+[）)]|" +
        "^第\\s*[一二三四五六七八九十百零\\d]+\\s*[章节部分条]|" +
        "^\\d+[、.．]");

    public List<Chunk> chunk(String text) {
        return chunk(text, defaultMaxChars, defaultMinChars, defaultOverlap);
    }

    public List<Chunk> chunkImage(String text) {
        return chunk(text, defaultMaxChars, 1, defaultOverlap);
    }

    /** Chunk parser-confirmed table rows without relying on question heuristics. */
    public List<Chunk> chunk(DocumentParseService.ParsedDocument document) {
        List<Chunk> chunks = new ArrayList<>();
        if (document == null) return chunks;

        for (DocumentParseService.QaRow row : document.qaRows()) {
            List<QaBoundaryDetector.NestedQa> nested =
                QaBoundaryDetector.nestedQuestionAnswers(row.question(), row.answer());
            if (nested.isEmpty()) {
                appendQaChunks(row.question(), row.answer(), defaultMaxChars, chunks);
            } else {
                for (QaBoundaryDetector.NestedQa unit : nested) {
                    appendQaChunks(unit.question(), unit.answer(), defaultMaxChars, chunks);
                }
            }
        }
        if (!document.unstructuredText().isBlank()) {
            // The parser has already made the structural Q&A decision for this
            // document. Do not run the looser paragraph heuristic again on the
            // remaining text and recreate false QA boundaries.
            chunks.addAll(chunkPlainText(document.unstructuredText()));
        }
        for (int index = 0; index < chunks.size(); index++) {
            chunks.get(index).position = index;
        }
        log.info("Chunked parsed document into {} chunks from {} confirmed Q&A rows",
            chunks.size(), document.qaRows().size());
        return chunks;
    }

    public List<Chunk> chunk(String text, int maxChars, int minChars, int overlap) {
        validateOptions(maxChars, minChars, overlap);
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        String normalized = normalize(text);
        List<TextSection> qaSections = splitQaSections(normalized);
        if (!qaSections.isEmpty()) {
            for (TextSection section : qaSections) {
                if (section.qa()) {
                    appendQaChunks(section.question(), section.answer(), maxChars, chunks);
                } else {
                    appendPlainChunks(section.content(), maxChars, minChars, overlap, chunks);
                }
            }
            log.info("Chunked Q&A document into {} knowledge units", chunks.size());
            return chunks;
        }

        appendPlainChunks(normalized, maxChars, minChars, overlap, chunks);
        log.info("Chunked into {} chunks", chunks.size());
        return chunks;
    }

    private List<Chunk> chunkPlainText(String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;
        appendPlainChunks(normalize(text), defaultMaxChars, defaultMinChars,
            defaultOverlap, chunks);
        return chunks;
    }

    private void appendPlainChunks(String text, int maxChars, int minChars, int overlap,
                                   List<Chunk> chunks) {
        if (text == null || text.isBlank()) return;
        for (PlainSection section : splitPlainSections(text)) {
            int contentBudget = overlap == 0 ? maxChars : maxChars - overlap - 1;
            List<String> baseChunks = pack(section.content(), contentBudget, minChars);
            for (int i = 0; i < baseChunks.size(); i++) {
                String content = baseChunks.get(i);
                if (i > 0 && overlap > 0) {
                    String prefix = completeSentenceTail(baseChunks.get(i - 1), overlap);
                    if (!prefix.isBlank() && prefix.length() + content.length() + 1 <= maxChars) {
                        content = prefix + "\n" + content;
                    }
                }
                addChunk(chunks, content, section.sectionPath());
            }
        }
    }

    private List<PlainSection> splitPlainSections(String text) {
        List<PlainSection> sections = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        String currentPath = "";

        for (String rawLine : text.split("\\n", -1)) {
            String line = rawLine.trim();
            Heading heading = parseHeading(line);
            if (heading != null) {
                addPlainSection(sections, content, currentPath);
                updateHeadingStack(headingStack, heading);
                currentPath = headingStack.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .reduce((left, right) -> left + " > " + right)
                    .orElse("");
                continue;
            }
            if (line.isEmpty()) {
                appendParagraphBreak(content);
            } else {
                if (content.length() > 0 && content.charAt(content.length() - 1) != '\n') {
                    content.append('\n');
                }
                content.append(line);
            }
        }
        addPlainSection(sections, content, currentPath);
        return sections;
    }

    private Heading parseHeading(String line) {
        if (line.isBlank()) return null;
        Matcher markdown = MARKDOWN_HEADER_RE.matcher(line);
        if (markdown.matches()) {
            return new Heading(markdown.group(1).length(), markdown.group(2).trim());
        }
        if (line.length() > 60 || !HEADER_RE.matcher(line).find()) return null;
        int level = 1;
        if (line.startsWith("（") || line.startsWith("(") || line.matches("^\\d+[、.．].*")) {
            level = 2;
        } else if (line.matches("^第.*[节条].*")) {
            level = 2;
        }
        return new Heading(level, line);
    }

    private void updateHeadingStack(List<String> headings, Heading heading) {
        while (headings.size() >= heading.level()) headings.remove(headings.size() - 1);
        while (headings.size() < heading.level() - 1) headings.add("");
        headings.add(heading.title());
    }

    private void addPlainSection(List<PlainSection> sections, StringBuilder content,
                                 String sectionPath) {
        String value = content.toString().trim();
        if (!value.isEmpty()) sections.add(new PlainSection(sectionPath, value));
        content.setLength(0);
    }

    private void appendParagraphBreak(StringBuilder content) {
        if (content.length() == 0) return;
        int length = content.length();
        if (content.charAt(length - 1) != '\n') content.append('\n');
        if (content.length() < 2 || content.charAt(content.length() - 2) != '\n') {
            content.append('\n');
        }
    }

    private List<String> pack(String text, int maxChars, int minChars) {
        List<String> units = splitUnits(text, maxChars);
        List<String> packed = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String unit : units) {
            int separator = buffer.length() == 0 ? 0 : 1;
            if (buffer.length() > 0 && buffer.length() + separator + unit.length() > maxChars) {
                packed.add(buffer.toString());
                buffer.setLength(0);
                separator = 0;
            }
            if (separator > 0) buffer.append('\n');
            buffer.append(unit);
        }
        if (buffer.length() > 0) packed.add(buffer.toString());

        if (packed.size() > 1) {
            int lastIndex = packed.size() - 1;
            String tail = packed.get(lastIndex);
            String previous = packed.get(lastIndex - 1);
            if (tail.length() < minChars && previous.length() + tail.length() + 1 <= maxChars) {
                packed.set(lastIndex - 1, previous + "\n" + tail);
                packed.remove(lastIndex);
            }
        }
        return packed;
    }

    private List<String> splitUnits(String text, int maxChars) {
        List<String> units = new ArrayList<>();
        for (String part : SENTENCE_BOUNDARY_RE.split(text)) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            if (value.length() <= maxChars) {
                units.add(value);
            } else {
                units.addAll(hardSplit(value, maxChars));
            }
        }
        return units;
    }

    private List<String> hardSplit(String text, int maxChars) {
        List<String> values = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length() && end > start
                    && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            values.add(text.substring(start, end));
            start = end;
        }
        return values;
    }

    private String completeSentenceTail(String value, int maxChars) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || !isSentenceEnd(text.charAt(text.length() - 1))) return "";
        int previousBoundary = -1;
        for (int i = text.length() - 2; i >= 0; i--) {
            if (isSentenceEnd(text.charAt(i))) {
                previousBoundary = i;
                break;
            }
        }
        String sentence = text.substring(previousBoundary + 1).trim();
        return sentence.length() <= maxChars ? sentence : "";
    }

    private boolean isSentenceEnd(char value) {
        return "。！？!?；;".indexOf(value) >= 0;
    }

    private List<TextSection> splitQaSections(String text) {
        String[] rawLines = text.split("\\n+");
        int questionCount = 0;
        boolean hasExplicitLabel = false;
        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (QaBoundaryDetector.hasExplicitQuestionLabel(line)) hasExplicitLabel = true;
            if (questionText(line) != null) questionCount++;
        }
        if (!hasExplicitLabel && questionCount < 2) return List.of();

        List<TextSection> sections = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        StringBuilder question = null;
        StringBuilder answer = null;
        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            String detectedQuestion = questionText(line);
            if (detectedQuestion != null) {
                if (question != null && answer != null && answer.length() > 0) {
                    sections.add(TextSection.qa(question.toString(), answer.toString()));
                    question = null;
                    answer = null;
                }
                if (question == null) {
                    if (plain.length() > 0) {
                        sections.add(TextSection.plain(plain.toString()));
                        plain.setLength(0);
                    }
                    question = new StringBuilder(detectedQuestion);
                    answer = new StringBuilder();
                } else {
                    question.append('\n').append(detectedQuestion);
                }
                continue;
            }

            String answerLine = QaBoundaryDetector.answerText(line);
            if (question != null) {
                if (!answerLine.isEmpty()) {
                    if (answer.length() > 0) answer.append('\n');
                    answer.append(answerLine);
                }
            } else {
                if (plain.length() > 0) plain.append('\n');
                plain.append(line);
            }
        }
        if (question != null) sections.add(TextSection.qa(question.toString(), answer.toString()));
        if (plain.length() > 0) sections.add(TextSection.plain(plain.toString()));
        return sections;
    }

    private String questionText(String line) {
        return QaBoundaryDetector.questionText(line);
    }

    private void appendQaChunks(String question, String answer, int maxChars,
                                List<Chunk> chunks) {
        String cleanQuestion = question == null ? "" : question.trim();
        String cleanAnswer = answer == null ? "" : answer.trim();
        if (cleanQuestion.isEmpty()) return;
        String context = KnowledgeTextUtil.truncate(cleanQuestion, 1000);
        if (cleanAnswer.isEmpty()) {
            for (String part : hardSplit(cleanQuestion, maxChars)) addChunk(chunks, part, context);
            return;
        }
        if (cleanQuestion.length() + cleanAnswer.length() + 1 <= maxChars) {
            addQaChunk(chunks, cleanQuestion, cleanAnswer,
                cleanQuestion + "\n" + cleanAnswer);
            return;
        }
        if (cleanQuestion.length() >= maxChars - 1) {
            String combined = cleanQuestion + "\n" + cleanAnswer;
            for (String part : pack(combined, maxChars, 1)) {
                addQaChunk(chunks, cleanQuestion, cleanAnswer, part);
            }
            return;
        }

        int answerBudget = maxChars - cleanQuestion.length() - 1;
        for (String answerPart : pack(cleanAnswer, answerBudget, 1)) {
            addQaChunk(chunks, cleanQuestion, cleanAnswer,
                cleanQuestion + "\n" + answerPart);
        }
    }

    private void addQaChunk(List<Chunk> chunks, String question, String fullAnswer,
                            String content) {
        addChunk(chunks, content, question);
        Chunk chunk = chunks.get(chunks.size() - 1);
        chunk.contentType = "QA";
        chunk.qaQuestion = question;
        chunk.qaAnswer = fullAnswer;
        chunk.qaKey = StructuredQaUtil.canonicalKey(question);
        chunk.qaGroupKey = StructuredQaUtil.sourceGroupKey(question, fullAnswer);
        chunk.qaVersion = 1;
    }

    private void addChunk(List<Chunk> chunks, String content, String sectionPath) {
        String value = content == null ? "" : content.trim();
        if (value.isEmpty()) return;
        Chunk chunk = new Chunk();
        chunk.content = value;
        chunk.context = sectionPath;
        chunk.sectionPath = sectionPath;
        chunk.position = chunks.size();
        chunk.charCount = value.length();
        chunk.strategyVersion = STRATEGY_VERSION;
        chunks.add(chunk);
    }

    private String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n')
            .replaceAll("[ \\t]+", " ").trim();
    }

    private void validateOptions(int maxChars, int minChars, int overlap) {
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars must be positive");
        if (minChars < 0 || minChars > maxChars) {
            throw new IllegalArgumentException("minChars must be between 0 and maxChars");
        }
        if (overlap < 0 || overlap >= maxChars - 1) {
            throw new IllegalArgumentException("overlap must leave room for chunk content");
        }
    }

    private record Heading(int level, String title) {}
    private record PlainSection(String sectionPath, String content) {}
    private record TextSection(boolean qa, String question, String answer, String content) {
        private static TextSection qa(String question, String answer) {
            return new TextSection(true, question, answer, "");
        }

        private static TextSection plain(String content) {
            return new TextSection(false, "", "", content);
        }
    }

    public static class Chunk {
        public String content;
        /** @deprecated use sectionPath */
        @Deprecated
        public String context;
        public String sectionPath;
        public int position;
        public int charCount;
        public String strategyVersion;
        public String contentType = "TEXT";
        public String qaQuestion;
        public String qaAnswer;
        public String qaKey;
        public String qaGroupKey;
        public int qaVersion = 1;

        public String embeddingText() {
            return KnowledgeTextUtil.chunkEmbeddingText(sectionPath, content);
        }
    }
}
