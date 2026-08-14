package com.feisheng.bot.admin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportQualityServiceTest {
    private final ChunkingService chunkingService = new ChunkingService();
    private final ImportQualityService qualityService = new ImportQualityService();

    @Test
    void passesWhenEverySourceRowProducesAnIndependentQaGroup() {
        DocumentParseService.ParsedDocument document = parsed(List.of(
            new DocumentParseService.QaRow("支持微信吗？", "支持。", "Sheet1", 2),
            new DocumentParseService.QaRow("如何开票？", "请联系客户经理。", "Sheet1", 3)), 2, 0);

        ImportQualityService.Assessment result =
            qualityService.assess(document, chunkingService.chunk(document));

        assertEquals(ImportQualityService.PASSED, result.status());
        assertFalse(result.blocked());
        assertEquals(2, result.sourceRowCount());
        assertEquals(2, result.detectedQaCount());
    }

    @Test
    void warnsForRepeatedQuestionWithDifferentAnswersWithoutBlockingImport() {
        DocumentParseService.ParsedDocument document = parsed(List.of(
            new DocumentParseService.QaRow("在哪里使用？", "支持微信。", "Sheet1", 2),
            new DocumentParseService.QaRow("在哪里使用?", "支持电脑端。", "Sheet1", 3)), 2, 0);

        ImportQualityService.Assessment result =
            qualityService.assess(document, chunkingService.chunk(document));

        assertEquals(ImportQualityService.WARNING, result.status());
        assertFalse(result.blocked());
        assertTrue(result.message().contains("重复问题"));
    }

    @Test
    void blocksRowsWithMissingQuestionsOrAnswers() {
        DocumentParseService.ParsedDocument document = parsed(List.of(
            new DocumentParseService.QaRow("有效问题？", "有效答案。", "Sheet1", 2)), 2, 1);

        ImportQualityService.Assessment result =
            qualityService.assess(document, chunkingService.chunk(document));

        assertEquals(ImportQualityService.BLOCKED, result.status());
        assertTrue(result.blocked());
        assertTrue(result.message().contains("缺少问题或答案"));
    }

    @Test
    void blocksImportWhenAnEmbeddedDocxImageCannotBeProcessed() {
        DocumentParseService.ParsedDocument document = new DocumentParseService.ParsedDocument(
            "foreign authentication:", "", List.of(
                new DocumentParseService.QaRow(
                    "Can a foreign user complete enterprise authentication?",
                    "Foreign users support the following methods:", "Sheet1", 2)),
            new DocumentParseService.ParseDiagnostics(true, 1, 1, 0, 1, 1));

        ImportQualityService.Assessment result =
            qualityService.assess(document, chunkingService.chunk(document));

        assertEquals(ImportQualityService.BLOCKED, result.status());
        assertTrue(result.blocked());
        assertTrue(result.message().contains("OCR"));
        assertEquals(1, result.sourceRowCount());
        assertEquals(0, result.invalidRowCount());
    }

    @Test
    void passesWhenOneCallbackSourceRowExpandsIntoIndependentQaGroups() {
        DocumentParseService.ParsedDocument document = parsed(List.of(
            new DocumentParseService.QaRow(
                "点签：您好，请问使用过程中有没有遇到疑问呢？", """
                点签：您好，请问您在使用我们应用的过程中，有没有遇到疑问呢
                发起多次后接收不到验证短信
                运营商会限制短信发送次数，请等待一天后再发起。
                更换法人认证
                新法人认证后，将新旧法人姓名和手机号提供给客服处理。
                用户个人签名存在重名
                签名名称不能相同，请修改签名名称。
                """, "Word 段落", 194)), 1, 0);

        ImportQualityService.Assessment result =
            qualityService.assess(document, chunkingService.chunk(document));

        assertEquals(ImportQualityService.PASSED, result.status());
        assertFalse(result.blocked());
        assertEquals(1, result.sourceRowCount());
        assertEquals(3, result.detectedQaCount());
        assertTrue(result.message().contains("1 行源数据已识别为 3 组问答"));
    }

    private DocumentParseService.ParsedDocument parsed(
            List<DocumentParseService.QaRow> rows, int sourceRows, int invalidRows) {
        return new DocumentParseService.ParsedDocument("", "", rows,
            new DocumentParseService.ParseDiagnostics(true, sourceRows,
                rows.size(), invalidRows));
    }
}
