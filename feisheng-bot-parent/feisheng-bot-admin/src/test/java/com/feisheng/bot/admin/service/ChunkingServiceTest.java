package com.feisheng.bot.admin.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkingServiceTest {
    private final ChunkingService service = new ChunkingService();

    @Test
    void keepsUnlabeledCustomerObjectionsWithTheirOwnAnswers() {
        String text = """
            e签宝说你们是小平台
            非常理解您的顾虑，平台规模只是选型因素之一，更重要的是业务匹配和服务响应。
            太贵了，还是签纸质的吧
            您好，完全理解您对成本的考量，可以结合签署量核算综合成本。
            使用点签电子合同平台签署合同，收费标准是什么？
            我们提供多种套餐方案，会根据合同签署数量和功能需求确定价格。
            """;

        List<ChunkingService.Chunk> chunks = service.chunk(text);

        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).content.startsWith("e签宝说你们是小平台\n"));
        assertTrue(chunks.get(0).content.contains("平台规模只是选型因素之一"));
        assertTrue(chunks.get(1).content.startsWith("太贵了，还是签纸质的吧\n"));
        assertTrue(chunks.get(2).content.startsWith("使用点签电子合同平台签署合同，收费标准是什么？\n"));
    }

    @Test
    void preservesShortLabeledQuestionAnswerUnits() {
        String text = """
            问题：支持免费试用吗？
            答案：支持。
            问题：怎么开票？
            答案：请联系客服确认开票信息。
            """;

        List<ChunkingService.Chunk> chunks = service.chunk(text);

        assertEquals(2, chunks.size());
        assertEquals("支持免费试用吗？\n支持。", chunks.get(0).content);
        assertEquals("怎么开票？\n请联系客服确认开票信息。", chunks.get(1).content);
        assertEquals("QA", chunks.get(0).contentType);
        assertEquals("支持免费试用吗？", chunks.get(0).qaQuestion);
        assertEquals("支持。", chunks.get(0).qaAnswer);
        assertFalse(chunks.get(0).qaKey.isBlank());
        assertFalse(chunks.get(0).qaGroupKey.isBlank());
        assertEquals(1, chunks.get(0).qaVersion);
    }

    @Test
    void keepsQuestionLikeAnswerProseInsideTheCurrentQaUnit() {
        List<ChunkingService.Chunk> chunks = service.chunk("""
            问题：合同如何收费？
            答案：按套餐确定。
            是否收费？这句话属于答案中的补充说明。
            问题：如何开票？
            答案：请联系客服。
            """);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).qaAnswer.contains("是否收费？这句话属于答案中的补充说明。"));
        assertEquals("如何开票？", chunks.get(1).qaQuestion);
    }

    @Test
    void keepsConfirmedSpreadsheetRowsIndependentWhenAnswersContainQuestionLikeLines() {
        DocumentParseService.ParsedDocument document = new DocumentParseService.ParsedDocument(
            "", "",
            List.of(
                new DocumentParseService.QaRow("第一问？",
                    "先完成认证。\n是否收费？这句话属于答案内容。", "工作表：问答", 2),
                new DocumentParseService.QaRow("第二问？", "这是第二个答案。", "工作表：问答", 3)),
            new DocumentParseService.ParseDiagnostics(true, 2, 2, 0));

        List<ChunkingService.Chunk> chunks = service.chunk(document);

        assertEquals(2, chunks.size());
        assertEquals("第一问？", chunks.get(0).qaQuestion);
        assertEquals("先完成认证。\n是否收费？这句话属于答案内容。", chunks.get(0).qaAnswer);
        assertEquals("第二问？", chunks.get(1).qaQuestion);
        assertEquals(2, chunks.stream().map(chunk -> chunk.qaGroupKey).distinct().count());
    }

    @Test
    void splitsCallbackScriptRowsIntoIndependentSupportQuestions() {
        DocumentParseService.ParsedDocument document = new DocumentParseService.ParsedDocument(
            "", "", List.of(new DocumentParseService.QaRow(
                "点签：您好，请问使用过程中有没有遇到疑问呢？", """
                点签：您好，请问您在使用我们应用的过程中，有没有遇到疑问呢
                发起多次后接收不到验证短信
                运营商会限制短信发送次数，请等待一天后再发起。
                更换法人认证
                新法人认证后，将新旧法人姓名和手机号提供给客服处理。
                用户个人签名存在重名
                签名名称不能相同，请修改签名名称。
                """, "Word 表格", 194)),
            new DocumentParseService.ParseDiagnostics(true, 1, 1, 0));

        List<ChunkingService.Chunk> chunks = service.chunk(document);

        assertEquals(3, chunks.size());
        assertEquals("发起多次后接收不到验证短信", chunks.get(0).qaQuestion);
        assertEquals("更换法人认证", chunks.get(1).qaQuestion);
        assertEquals("用户个人签名存在重名", chunks.get(2).qaQuestion);
        assertTrue(chunks.stream().noneMatch(chunk ->
            chunk.qaAnswer.contains("点签：您好，请问您在使用")));
    }

    @Test
    void keepsOrdinaryStructuredAnswersWithQuestionLikeLinesIntact() {
        String answer = "先完成认证。\n是否收费？这句话属于答案内容。\n请联系客服确认。";
        DocumentParseService.ParsedDocument document = new DocumentParseService.ParsedDocument(
            "", "", List.of(new DocumentParseService.QaRow(
                "第一问？", answer, "工作表：问答", 2)),
            new DocumentParseService.ParseDiagnostics(true, 1, 1, 0));

        List<ChunkingService.Chunk> chunks = service.chunk(document);

        assertEquals(1, chunks.size());
        assertEquals("第一问？", chunks.get(0).qaQuestion);
        assertEquals(answer, chunks.get(0).qaAnswer);
    }

    @Test
    void doesNotReclassifyUnstructuredRemainderAfterParserConfirmation() {
        DocumentParseService.ParsedDocument document = new DocumentParseService.ParsedDocument(
            "", "普通说明。\n没有对应答案的问题？\n后续说明。", List.of(
                new DocumentParseService.QaRow("已确认问题？", "已确认答案。", "Word 段落", 2)),
            new DocumentParseService.ParseDiagnostics(true, 1, 1, 0));

        List<ChunkingService.Chunk> chunks = service.chunk(document);

        assertEquals(2, chunks.size());
        assertEquals("QA", chunks.get(0).contentType);
        assertEquals("TEXT", chunks.get(1).contentType);
        assertTrue(chunks.get(1).content.contains("没有对应答案的问题？"));
    }

    @Test
    void keepsExplicitOcrTableRelationshipsAfterChunkNormalisation() {
        String answer = "外国友人仅支持以下方式认证：\n"
            + "[结构化表格]\n"
            + "表头：证件类型；手机认证；人脸认证；银行卡认证；人工审核认证\n"
            + "表格行：证件类型=国际护照；手机认证=×；人脸认证=×；银行卡认证=√ (数据宝)；人工审核认证=√\n"
            + "[/结构化表格]";
        DocumentParseService.ParsedDocument document = new DocumentParseService.ParsedDocument(
            "", "", List.of(new DocumentParseService.QaRow(
                "客户是外籍人士，没有中国大陆手机号，能完成企业认证吗？", answer,
                "Word 图片 OCR", 1)),
            new DocumentParseService.ParseDiagnostics(true, 1, 1, 0));

        List<ChunkingService.Chunk> chunks = service.chunk(document);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).qaAnswer.contains("证件类型=国际护照"));
        assertTrue(chunks.get(0).qaAnswer.contains("手机认证=×"));
        assertTrue(chunks.get(0).qaAnswer.contains("银行卡认证=√ (数据宝)"));
        assertFalse(chunks.get(0).content.contains("\t"));
        assertTrue(chunks.get(0).embeddingText().contains("人脸认证=×"));
    }

    @Test
    void neverDropsShortDocumentsOrShortSections() {
        String text = """
            # 第一章
            很短。
            # 第二章
            另一段也很短。
            """;

        List<ChunkingService.Chunk> chunks = service.chunk(text);

        assertEquals(2, chunks.size());
        assertEquals("很短。", chunks.get(0).content);
        assertEquals("第一章", chunks.get(0).sectionPath);
        assertEquals("另一段也很短。", chunks.get(1).content);
        assertEquals("第二章", chunks.get(1).sectionPath);
    }

    @Test
    void keepsMarkdownHeadingHierarchyInEmbeddingText() {
        List<ChunkingService.Chunk> chunks = service.chunk("""
            # 员工手册
            ## 请假制度
            年假需要提前申请。
            """);

        assertEquals(1, chunks.size());
        assertEquals("员工手册 > 请假制度", chunks.get(0).sectionPath);
        assertEquals("员工手册 > 请假制度\n年假需要提前申请。",
            chunks.get(0).embeddingText());
        assertEquals(ChunkingService.STRATEGY_VERSION, chunks.get(0).strategyVersion);
    }

    @Test
    void enforcesHardMaximumForLongUnpunctuatedTextAndLongQaAnswers() {
        String plain = "甲".repeat(1600);
        String qa = "问题：如何处理？\n答案：" + "乙".repeat(1600);

        List<ChunkingService.Chunk> plainChunks = service.chunk(plain);
        List<ChunkingService.Chunk> qaChunks = service.chunk(qa);

        assertTrue(plainChunks.size() > 1);
        assertTrue(qaChunks.size() > 1);
        assertTrue(plainChunks.stream().allMatch(chunk -> chunk.charCount <= 600));
        assertTrue(qaChunks.stream().allMatch(chunk -> chunk.charCount <= 600));
        assertEquals(1600, plainChunks.stream().mapToInt(chunk -> chunk.content.length()).sum());
        assertTrue(qaChunks.stream().allMatch(chunk -> "QA".equals(chunk.contentType)));
        assertTrue(qaChunks.stream().allMatch(chunk -> "乙".repeat(1600).equals(chunk.qaAnswer)));
        assertEquals(1, qaChunks.stream().map(chunk -> chunk.qaKey).distinct().count());
        assertEquals(1, qaChunks.stream().map(chunk -> chunk.qaGroupKey).distinct().count());
    }

    @Test
    void overlapsOnlyWithACompleteTrailingSentence() {
        String first = "甲".repeat(450) + "。";
        String overlapSentence = "需要保留。";
        String second = "乙".repeat(100) + "。";

        List<ChunkingService.Chunk> chunks = service.chunk(
            first + overlapSentence + second, 600, 50, 80);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(1).content.startsWith(overlapSentence + "\n"));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.charCount <= 600));
    }

    @Test
    void doesNotSplitSurrogatePairsAtHardBoundary() {
        List<ChunkingService.Chunk> chunks = service.chunk("😀".repeat(400), 101, 1, 0);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.charCount <= 101));
        assertEquals("😀".repeat(400),
            chunks.stream().map(chunk -> chunk.content).reduce("", String::concat));
    }

    @Test
    void rejectsOverlapThatLeavesNoContentBudget() {
        assertThrows(IllegalArgumentException.class,
            () -> service.chunk("内容", 80, 1, 79));
    }
}
