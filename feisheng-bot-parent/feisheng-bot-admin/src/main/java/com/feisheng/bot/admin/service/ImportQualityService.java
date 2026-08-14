package com.feisheng.bot.admin.service;

import com.feisheng.bot.common.util.StructuredQaUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Validates that table-based Q&A imports keep their source-row boundaries. */
@Service
public class ImportQualityService {
    public static final String PROCESSING = "PROCESSING";
    public static final String PASSED = "PASSED";
    public static final String WARNING = "WARNING";
    public static final String BLOCKED = "BLOCKED";

    private static final int WARNING_QUESTION_CHARS = 300;
    private static final int MAX_QUESTION_CHARS = 1000;
    private static final int WARNING_ANSWER_CHARS = 3000;
    private static final int MAX_ANSWER_CHARS = 8000;

    public Assessment assess(DocumentParseService.ParsedDocument document,
                             List<ChunkingService.Chunk> chunks) {
        DocumentParseService.ParseDiagnostics diagnostics = document.diagnostics();
        int detectedQaCount = (int) chunks.stream()
            .filter(chunk -> "QA".equals(chunk.contentType))
            .map(chunk -> chunk.qaGroupKey)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .count();

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (diagnostics.unprocessedImageCount() > 0) {
            blockers.add("有 " + diagnostics.unprocessedImageCount()
                + " 张 DOCX 内嵌图片未完成 OCR，已阻止导入");
        }
        if (!diagnostics.structuredQaDetected()) {
            if (!blockers.isEmpty()) {
                return new Assessment(BLOCKED, String.join("；", blockers),
                    diagnostics.sourceRowCount(), detectedQaCount,
                    diagnostics.invalidRowCount());
            }
            return new Assessment(PASSED,
                "普通文档检查通过，共生成 " + chunks.size() + " 个切片",
                0, detectedQaCount, 0);
        }

        if (diagnostics.invalidRowCount() > 0) {
            blockers.add(diagnostics.invalidRowCount() + " 行缺少问题或答案");
        }
        if (diagnostics.sourceRowCount()
                != diagnostics.validQaRowCount() + diagnostics.invalidRowCount()) {
            blockers.add("源数据行数与解析结果不一致");
        }

        List<DocumentParseService.QaRow> qualityRows = expandedQaRows(document.qaRows());
        Set<String> expectedGroups = new HashSet<>();
        Set<String> canonicalQuestions = new LinkedHashSet<>();
        int duplicateQuestions = 0;
        int longQuestions = 0;
        int longAnswers = 0;
        for (DocumentParseService.QaRow row : qualityRows) {
            expectedGroups.add(StructuredQaUtil.sourceGroupKey(row.question(), row.answer()));
            String questionKey = StructuredQaUtil.canonicalKey(row.question());
            if (!canonicalQuestions.add(questionKey)) duplicateQuestions++;
            if (row.question().length() > MAX_QUESTION_CHARS) {
                blockers.add("第 " + row.sourceRowNumber() + " 行问题超过 "
                    + MAX_QUESTION_CHARS + " 字");
            } else if (row.question().length() > WARNING_QUESTION_CHARS) {
                longQuestions++;
            }
            if (row.answer().length() > MAX_ANSWER_CHARS) {
                blockers.add("第 " + row.sourceRowNumber() + " 行答案超过 "
                    + MAX_ANSWER_CHARS + " 字");
            } else if (row.answer().length() > WARNING_ANSWER_CHARS) {
                longAnswers++;
            }
        }

        Set<String> actualGroups = new HashSet<>();
        chunks.stream()
            .filter(chunk -> "QA".equals(chunk.contentType))
            .map(chunk -> chunk.qaGroupKey)
            .filter(value -> value != null && !value.isBlank())
            .forEach(actualGroups::add);
        long missingGroups = expectedGroups.stream().filter(key -> !actualGroups.contains(key)).count();
        if (missingGroups > 0) blockers.add(missingGroups + " 行问答未生成独立知识单元");
        if (expectedGroups.size() != qualityRows.size()) {
            blockers.add("存在完全重复的问答行，请合并后重新上传");
        }

        double duplicateRatio = qualityRows.isEmpty() ? 0
            : (double) duplicateQuestions / qualityRows.size();
        if (duplicateQuestions >= 3 && duplicateRatio >= 0.25) {
            blockers.add("重复问题占比过高（" + percent(duplicateRatio) + "）");
        } else if (duplicateQuestions > 0) {
            warnings.add("发现 " + duplicateQuestions + " 个重复问题，请确认答案差异");
        }
        if (longQuestions > 0) warnings.add(longQuestions + " 个问题较长");
        if (longAnswers > 0) warnings.add(longAnswers + " 个答案较长");

        String summary = diagnostics.sourceRowCount() + " 行源数据已识别为 "
            + expectedGroups.size() + " 组问答";
        if (!blockers.isEmpty()) {
            return new Assessment(BLOCKED, summary + "；" + String.join("；", blockers),
                diagnostics.sourceRowCount(), detectedQaCount, diagnostics.invalidRowCount());
        }
        if (!warnings.isEmpty()) {
            return new Assessment(WARNING, summary + "；" + String.join("；", warnings),
                diagnostics.sourceRowCount(), detectedQaCount, diagnostics.invalidRowCount());
        }
        return new Assessment(PASSED, summary + "，结构检查通过",
            diagnostics.sourceRowCount(), detectedQaCount, diagnostics.invalidRowCount());
    }

    private List<DocumentParseService.QaRow> expandedQaRows(
            List<DocumentParseService.QaRow> sourceRows) {
        List<DocumentParseService.QaRow> expanded = new ArrayList<>();
        for (DocumentParseService.QaRow row : sourceRows) {
            List<QaBoundaryDetector.NestedQa> nested =
                QaBoundaryDetector.nestedQuestionAnswers(row.question(), row.answer());
            if (nested.isEmpty()) {
                expanded.add(row);
                continue;
            }
            for (QaBoundaryDetector.NestedQa qa : nested) {
                expanded.add(new DocumentParseService.QaRow(
                    qa.question(), qa.answer(), row.source(), row.sourceRowNumber()));
            }
        }
        return expanded;
    }

    private String percent(double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    public record Assessment(String status, String message, int sourceRowCount,
                             int detectedQaCount, int invalidRowCount) {
        public boolean blocked() {
            return BLOCKED.equals(status);
        }
    }
}
