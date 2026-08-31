package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.dto.QueryVariant;
import com.feisheng.bot.core.dto.SafetyResult;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotAiReplyLogMapper;
import com.feisheng.bot.core.service.BusinessSafetyBoundaryService;
import com.feisheng.bot.core.service.ConversationServiceImpl;
import com.feisheng.bot.core.service.CustomerServicePromptProvider;
import com.feisheng.bot.core.service.EmotionService;
import com.feisheng.bot.core.service.HandoffCoordinator;
import com.feisheng.bot.core.service.IntentService;
import com.feisheng.bot.core.service.IntentUnderstandingService;
import com.feisheng.bot.core.service.NlpIntentClassifier;
import com.feisheng.bot.core.service.ReplyAttachmentService;
import com.feisheng.bot.core.service.SensitiveDataService;
import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DialogServiceImplTest {
    private static final Map<String, Object> KNOWLEDGE_RETRIEVAL_FILTERS =
        Map.of("sourceScope", "KNOWLEDGE");
    private static final String SCOPE_FALLBACK_REPLY =
        "非常抱歉，我仅擅长处理点签电子合同相关业务。"
            + "您刚才的问题不在我的服务范围内。如果您有合同方面的疑问，欢迎随时提出，"
            + "我会全力为您解答；";
    private static final String PARENT_COMPANY_REPLY = """
        您好，非常抱歉，我们这边主要是做互联网电子合同业务的，不属于此项业务板块，建议您通过以下途径解决：
        ① （推荐）添加QQ在线服务群，联系在线客服答疑QQ群：
        一群：575467556
        二群：769414652
        三群：856201972
        四群：795382083
        五群：518585994
        六群：745732151
        七群：746835832
        八群：786045605
        九群：612640416
        进入【江苏翔晟服务群】咨询在线客服。
        ② 进入翔晟官网自助查询及办理
        官网链接：http://www.share-sun.com.cn/index.php/zxfw/
        ③ 拨打客服热线电话：025-66085508。
        """.strip();

    @Mock private ConversationServiceImpl conversationService;
    @Mock private MessageServiceImpl messageService;
    @Mock private AiModelServiceImpl aiModelService;
    @Mock private SafetyServiceImpl safetyService;
    @Mock private BotAiReplyLogMapper aiReplyLogMapper;
    @Mock private RagRetrievalService retrievalService;
    @Mock private UnmatchedQuestionService unmatchedQuestionService;
    @Mock private BusinessToolOrchestrator businessToolOrchestrator;
    @Mock private IntentService intentService;
    @Mock private IntentUnderstandingService intentUnderstandingService;
    @Mock private HandoffCoordinator handoffCoordinator;
    @Mock private ReplyAttachmentService replyAttachmentService;

    private DialogServiceImpl dialogService;

    @BeforeEach
    void setUp() {
        dialogService = new DialogServiceImpl(
            conversationService, messageService, aiModelService, safetyService,
            new BusinessSafetyBoundaryService(),
            new CustomerServicePromptProvider("v1", "rag-system-prompt"),
            aiReplyLogMapper, retrievalService, unmatchedQuestionService,
            businessToolOrchestrator, intentService,
            new NlpIntentClassifier(),
            new SensitiveDataService("18689633999"),
            handoffCoordinator, new EmotionService(), replyAttachmentService,
            new ContextualQueryResolver(new ObjectMapper()), intentUnderstandingService,
            new ObjectMapper());
        ReflectionTestUtils.setField(dialogService, "noAnswerReply", SCOPE_FALLBACK_REPLY);
        ReflectionTestUtils.setField(dialogService, "outOfScopeReply", PARENT_COMPANY_REPLY);
        ReflectionTestUtils.setField(dialogService, "unrelatedReply", SCOPE_FALLBACK_REPLY);
        ReflectionTestUtils.setField(dialogService, "errorReply", "当前查询出现异常，请稍后重试");
        ReflectionTestUtils.setField(dialogService, "outOfScopeKeywords",
            "CA锁,实体锁,UKey,U-Key,安全控件,守信签,总部电子签章,翔晟电子签章");
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", true);
        ReflectionTestUtils.setField(dialogService, "lowConfidenceThreshold", 0.55);
        ReflectionTestUtils.setField(dialogService, "maxHistoryMessages", 6);
        ReflectionTestUtils.setField(dialogService, "maxRetrievalHistoryMessages", 4);
        ReflectionTestUtils.setField(dialogService, "maxRetrievalHistoryChars", 1200);
        ReflectionTestUtils.setField(dialogService, "maxPromptTokens", 4000);
        ReflectionTestUtils.setField(dialogService, "contextSummaryEnabled", false);
        ReflectionTestUtils.setField(dialogService, "contextSummaryTriggerRatio", 0.80);
        ReflectionTestUtils.setField(dialogService, "contextSummaryTargetRatio", 0.50);
        ReflectionTestUtils.setField(dialogService, "contextSummaryKeepMessages", 4);
        ReflectionTestUtils.setField(dialogService, "contextSummaryMaxChars", 4000);
        ReflectionTestUtils.setField(dialogService, "contextSummaryModelId", 0L);
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", false);
        ReflectionTestUtils.setField(dialogService, "nativeFallbackHighRiskKeywords",
            "合同条款,法律效力,违约责任,赔偿责任,合规承诺,保证结果,隐私泄露,个人信息泄露");
        ReflectionTestUtils.setField(dialogService, "nativeFallbackClarificationKeywords",
            "这个,那个,它,这款,那款,然后呢");
        ReflectionTestUtils.setField(dialogService, "nativeFallbackClarificationReply", "请补充具体场景");
        ReflectionTestUtils.setField(dialogService, "nativeFallbackHighRiskReply", "高风险问题转人工确认");
        ReflectionTestUtils.setField(dialogService, "nativeFallbackHighRiskTransfer", true);
        ReflectionTestUtils.setField(dialogService, "nativeFallbackSystemPrompt", "native-system-prompt");
        ReflectionTestUtils.setField(dialogService, "businessToolsEnabled", true);
        ReflectionTestUtils.setField(dialogService, "intentStaticRepliesEnabled", true);
        ReflectionTestUtils.setField(dialogService, "priceHandoffKeywords",
            "定制报价,专属报价,商务报价,最终报价,折扣,优惠,议价,最低价,底价,便宜点,打折");
        ReflectionTestUtils.setField(dialogService, "priceHandoffReply", "价格问题已提交人工确认");
        ReflectionTestUtils.setField(dialogService, "priceHandoffFailedReply", "价格工单提交失败");

        BotConversation conversation = new BotConversation();
        conversation.setId(10L);
        lenient().when(conversationService.getOrCreate(anyString(), anyString(), anyString()))
            .thenReturn(conversation);
        lenient().when(messageService.getByConversation(10L)).thenReturn(Collections.emptyList());
        lenient().when(safetyService.checkUserInput(anyString())).thenReturn(SafetyResult.pass());
        lenient().when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());
        lenient().when(handoffCoordinator.handoff(any(), anyString(), anyString())).thenReturn(
            new HandoffCoordinator.HandoffResult(true, 88L, true, "测试摘要", null));
        lenient().when(replyAttachmentService.fromCitations(any(), anyBoolean()))
            .thenReturn(Collections.emptyList());
        lenient().when(replyAttachmentService.fromKnowledgeImageTitle(anyString()))
            .thenReturn(Collections.emptyList());
        lenient().when(intentService.match(anyString())).thenReturn(Optional.empty());
        lenient().when(intentUnderstandingService.understand(
                anyString(), anyList(), nullable(Long.class)))
            .thenReturn(IntentUnderstandingService.Understanding.notAttempted("test_default"));

        // Preserve the shorter-overload stubs used by behavior tests while requiring
        // every production path to carry the fixed knowledge-scope filter.
        lenient().when(retrievalService.retrieve(
                anyString(), eq(KNOWLEDGE_RETRIEVAL_FILTERS), eq(true)))
            .thenAnswer(invocation -> retrievalService.retrieve(invocation.getArgument(0)));
        lenient().when(retrievalService.retrieve(
                anyString(), nullable(String.class), nullable(String.class),
                eq(KNOWLEDGE_RETRIEVAL_FILTERS), eq(true)))
            .thenAnswer(invocation -> {
                String query = invocation.getArgument(0);
                String conversationContext = invocation.getArgument(1);
                String modalityContext = invocation.getArgument(2);
                return conversationContext == null
                    ? retrievalService.retrieve(query, modalityContext, true)
                    : retrievalService.retrieve(
                        query, conversationContext, modalityContext, true);
            });
        lenient().when(retrievalService.retrieve(
                anyString(), nullable(String.class), nullable(String.class),
                eq(KNOWLEDGE_RETRIEVAL_FILTERS), anyList(), eq(true)))
            .thenAnswer(invocation -> {
                List<QueryVariant> variants = invocation.getArgument(4);
                String fallbackQuery = invocation.getArgument(0);
                if (variants != null) {
                    fallbackQuery = variants.stream()
                        .filter(variant -> "intent_rewrite".equals(variant.purpose()))
                        .map(QueryVariant::query)
                        .findFirst()
                        .orElse(fallbackQuery);
                }
                return retrievalService.retrieve(fallbackQuery);
            });
    }

    @Test
    void usesProvidedRagContextAndReturnsCitationContract() {
        String context = "【参考知识库内容】\n问题：如何重置密码\n答案：在登录页点击忘记密码。";
        Map<String, Object> citation = citation("provided:1", "provided_context", null, "调用方上下文");
        when(retrievalService.citationsForProvidedContext(context)).thenReturn(List.of(citation));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")
                    && prompt.contains("强制事实与安全边界")), eq(null)))
            .thenReturn(new ChatResponse("**在登录页点击忘记密码。**[1]", true,
                "test-model", "test", 20, 8));

        Map<String, Object> result = dialogService.send(
            "playground", "admin-preview", "密码忘了怎么办", "试聊", context);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(promptCaptor.capture(), argThat(prompt ->
            prompt.startsWith("rag-system-prompt")
                && prompt.contains("强制事实与安全边界")), eq(null));
        assertTrue(promptCaptor.getValue().contains(context));
        assertTrue(promptCaptor.getValue().contains("明确点名的产品、业务或对象为唯一主体"));
        assertTrue(promptCaptor.getValue().contains("优势、特点、介绍或比较类问题"));
        assertTrue(promptCaptor.getValue().contains("禁止使用 Markdown"));
        assertTrue(promptCaptor.getValue().contains("按问题复杂度选择最小充分结构"));
        assertTrue(promptCaptor.getValue().contains("简单事实或解释类问题直接用一至三句话回答"));
        assertTrue(promptCaptor.getValue().contains("操作、流程或排查类问题"));
        assertTrue(promptCaptor.getValue().contains("缺少依据的栏目必须省略"));
        assertTrue(promptCaptor.getValue().contains("优先采用表述更具体且限制更严格的规则"));
        assertTrue(promptCaptor.getValue().contains("不能在首句结论后提前结束"));
        assertEquals("rag_ai", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals(true, result.get("ragSource"));
        assertEquals(context.length(), result.get("ragContextChars"));
        assertEquals(List.of(citation), result.get("citations"));
        assertEquals("在登录页点击忘记密码。", result.get("reply"));
        assertEquals("test-model", result.get("model"));
        assertEquals(20, result.get("inputTokens"));
        assertEquals(true, result.get("promptApplied"));
        assertEquals("rag", result.get("promptPath"));
        assertEquals("configured_v1", result.get("promptSource"));
        assertEquals(64, ((String) result.get("promptBaseSha256")).length());
        assertEquals(64, ((String) result.get("promptEffectiveSha256")).length());
        assertEquals(1, ((List<?>) result.get("promptInvocations")).size());
        assertTrue(result.containsKey("stageLatencies"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stageLatencies =
            (Map<String, Object>) result.get("stageLatencies");
        assertTrue(stageLatencies.containsKey("dialogTotalMs"));
        assertTrue(stageLatencies.containsKey("retrievalMs"));
        assertTrue(stageLatencies.containsKey("embeddingMs"));
        assertTrue(stageLatencies.containsKey("vectorSearchMs"));
        assertTrue(stageLatencies.containsKey("sparseSearchMs"));
        assertTrue(stageLatencies.containsKey("rerankMs"));
        assertTrue(stageLatencies.containsKey("modelMs"));
        assertTrue(((Number) stageLatencies.get("dialogTotalMs")).longValue() >= 0);
        assertTrue(((Number) stageLatencies.get("modelMs")).longValue() >= 0);
    }

    @Test
    void sendsExplicitV2SystemPromptAndReturnsResolvedVersion() {
        String context = "【参考知识库内容】\n问题：合同能否直接修改\n答案：不能直接修改，需要撤回后重新发起。";
        when(retrievalService.citationsForProvidedContext(context)).thenReturn(List.of(
            citation("provided:v2", "provided_context", null, "调用方上下文")));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(new ChatResponse("不能直接修改，需要撤回后重新发起。", true,
                "test-model", "test", 20, 8));

        Map<String, Object> result = dialogService.send(
            "evaluation", "prompt-v2", "这个能直接改吗？", "V2 评测",
            context, null, "v2");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(anyString(), systemPrompt.capture(), isNull());
        assertTrue(systemPrompt.getValue().contains("点签电子合同"));
        assertTrue(systemPrompt.getValue().contains("未提及某项能力，不等于点签不支持"));
        assertTrue(systemPrompt.getValue().contains("总数与随后明确列出的项目数量不一致"));
        assertEquals("v2", result.get("promptVersion"));
        assertEquals("rag_ai", result.get("source"));
        assertEquals(true, result.get("promptApplied"));
        assertEquals("built_in_v2", result.get("promptSource"));
        assertTrue(systemPrompt.getValue().contains("通用法律知识不得用于推导点签产品支持"));
    }

    @Test
    void createsManualHandoffBeforeKnowledgeRetrieval() {
        Map<String, Object> result = dialogService.send(
            "dingtalk", "manual-handoff-user", "转人工", "咨询");

        assertEquals("handoff", result.get("source"));
        assertEquals("handoff_requested", result.get("answerStatus"));
        assertEquals("HANDOFF", result.get("answerDecision"));
        assertEquals("WAITING", result.get("handoffStatus"));
        assertEquals(true, result.get("needsTransfer"));
        assertTrue(((String) result.get("reply")).contains("人工客服请求"));
        assertEquals(88L, ((Map<?, ?>) result.get("handoff")).get("ticketId"));

        verify(handoffCoordinator).handoff(10L, "客户主动请求人工客服", "P1");
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void recognizesPoliteManualHandoffRequestAndDoesNotTreatCancellationAsRequest() {
        Map<String, Object> request = dialogService.send(
            "playground", "manual-handoff-request", "请帮我转人工处理", "试聊");

        assertEquals("handoff_requested", request.get("answerStatus"));
        assertTrue(((String) request.get("reply")).contains("人工客服请求"));
        verify(handoffCoordinator).handoff(10L, "客户主动请求人工客服", "P1");

        Map<String, Object> cancellation = dialogService.send(
            "playground", "manual-handoff-cancel", "取消转人工", "试聊");

        assertFalse("handoff".equals(cancellation.get("source")));
        verify(handoffCoordinator, times(1))
            .handoff(10L, "客户主动请求人工客服", "P1");
    }

    @Test
    void requestsDetailedBreakdownForListQuestions() {
        when(retrievalService.retrieve("点签电子合同有几种服务模式？")).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "三种服务模式及其适用场景", 0.95, "answered", false,
                List.of(citation("chunk:1", "document", 1L, "服务模式")),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), any()))
            .thenReturn(new ChatResponse("点签电子合同有3种服务模式：\n\n- SaaS模式：适合快速使用。\n"
                + "- OpenAPI接口模式：适合系统集成。\n- 定制化开发模式：适合特殊需求。",
                true, "test-model", "test", 20, 30));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "dingtalk", "list-question-user", "点签电子合同有几种服务模式？", "咨询");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(anyString(), systemPrompt.capture(), any());
        assertTrue(systemPrompt.getValue().contains("请在首句明确总数"));
        assertTrue(systemPrompt.getValue().contains("使用数字序号"));
        assertTrue(systemPrompt.getValue().contains("名称、核心作用或特点、适用场景"));
        assertEquals("rag_ai", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        verify(retrievalService).retrieve(
            "点签电子合同有几种服务模式？", KNOWLEDGE_RETRIEVAL_FILTERS, true);
    }

    @Test
    void routesOpenEndedDianqianQuestionToProductOverviewRetrievalAndPrompt() {
        String question = "我想了解一下你们点签";
        String overviewQuery = "点签电子合同产品介绍 定位 核心功能 安全合规 使用入口 适用场景 产品优势";
        when(retrievalService.retrieve(overviewQuery)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null,
                "点签是电子合同平台，支持电子签名、合同模板和合同管理；"
                    + "支持微信公众号、微信小程序、PC网页版和钉钉；"
                    + "提供实名认证、存证和时间戳，适用于销售合同和劳动合同。",
                0.93, "answered", false,
                List.of(citation("chunk:overview", "document", 9L, "点签产品介绍")),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), any()))
            .thenReturn(new ChatResponse(
                "点签是电子合同平台，支持电子签名、合同模板和合同管理。"
                    + "可通过微信公众号、微信小程序、PC网页版和钉钉使用，"
                    + "并提供实名认证、存证和时间戳，适用于销售、劳动等合同场景。",
                true, "test-model", "test", 30, 45));

        Map<String, Object> result = dialogService.send(
            "web", "product-overview-user", question, "咨询");

        assertEquals("PRODUCT_OVERVIEW",
            ((Map<?, ?>) result.get("nlpIntent")).get("intentCode"));
        assertEquals(overviewQuery, result.get("retrievalPrimaryQuery"));
        assertTrue(((List<?>) result.get("retrievalVariants")).stream()
            .anyMatch(value -> value.toString().contains("核心功能和产品优势")));
        assertEquals("rag_ai", result.get("source"));
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(anyString(), systemPrompt.capture(), any());
        assertTrue(systemPrompt.getValue().contains("客户当前是在了解点签产品"));
    }

    @Test
    void marksProductOverviewPartialWhenEvidenceCoversOnlyOneArea() {
        String overviewQuery = "点签电子合同产品介绍 定位 核心功能 安全合规 使用入口 适用场景 产品优势";
        when(retrievalService.retrieve(overviewQuery)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "点签可以通过微信小程序使用。", 0.92,
                "answered", false,
                List.of(citation("chunk:channel-only", "document", 10L, "使用入口")),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), any()))
            .thenReturn(new ChatResponse(
                "__ANSWER_PARTIAL__\n目前可确认点签可以通过微信小程序使用。"
                    + "您更想了解核心功能、安全合规还是适用场景？",
                true, "test-model", "test", 20, 30));

        Map<String, Object> result = dialogService.send(
            "web", "product-overview-partial", "介绍一下点签", "咨询");

        assertEquals("ANSWER_PARTIAL", result.get("answerDecision"));
        assertEquals("partial", result.get("answerMode"));
        assertTrue(((String) result.get("reply")).contains("更想了解"));
    }

    @Test
    void answersBroadProductUsageWithVerifiedEntriesInsteadOfInventedSteps() {
        String question = "怎么使用？";
        String verifiedAnswer = "点签支持通过钉钉、微信公众号、微信小程序、PC 网页版、"
            + "企业微信和短信签署链接使用。目前不提供独立手机 APP；手机用户可以通过"
            + "微信公众号、微信小程序或短信签署链接办理相关操作。";
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "你们是做什么的？"),
            message("ai", "我们是电子合同平台，用户可全程在线上发起以及签署。"),
            message("user", question)));
        when(retrievalService.retrieve("点签可以在哪里使用？")).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null,
                "问题：点签可以在哪里使用？\n答案：" + verifiedAnswer
                    + "\n事实：不提供独立手机 APP。",
                0.91, "answered", false,
                List.of(citation("chunk:channels", "document", 3L, "点签使用入口")),
                Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "operational-user", question, "咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("evidence_consistency_guardrail", result.get("fallbackDecision"));
        assertEquals("点签可以在哪里使用？", result.get("retrievalPrimaryQuery"));
        assertTrue(((String) result.get("reply")).contains("不提供独立手机 APP"));
        assertTrue(((String) result.get("reply")).contains("微信小程序"));
        assertTrue(((String) result.get("reply")).contains("具体想进行发起合同"));
        assertFalse(((String) result.get("reply")).contains("应用商店"));
        assertEquals("operation",
            ((Map<?, ?>) result.get("pendingClarification")).get("missingSlot"));
        assertEquals("CLARIFY",
            ((Map<?, ?>) result.get("serviceDecision")).get("decision"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void separatesUnsignedFileLaunchFromSignedPaperContractArchiving() {
        String question = "我已经有合同了，怎么上传？";
        String retrievalQuery = "发起合同有几种方式？ 已有合同文件怎么上传发起签署，"
            + "已签纸质合同怎么上传归档？";
        RagRetrievalService.RetrievalResult launchEvidence =
            new RagRetrievalService.RetrievalResult(
                true, true, "未签署合同文件可以上传发起签署。",
                "未签署合同文件可以上传发起签署。", 1.0, "direct", false,
                List.of(citation("faq:launch", "faq", 4L, "发起合同有几种方式？")),
                Collections.emptyList());
        RagRetrievalService.RetrievalResult archiveEvidence =
            new RagRetrievalService.RetrievalResult(
                true, true, "已签纸质合同扫描后可以上传归档。",
                "已签纸质合同扫描后可以上传归档。", 1.0, "direct", false,
                List.of(citation("faq:archive", "faq", 5L,
                    "怎么把之前的纸质合同上传到点签里？")),
                Collections.emptyList());
        RagRetrievalService.RetrievalResult mergedEvidence =
            new RagRetrievalService.RetrievalResult(
                true, false, null,
                "未签署合同文件可以上传发起签署；已签纸质合同扫描后可以上传归档。",
                1.0, "multimodal_rag", false,
                List.of(archiveEvidence.citations().get(0), launchEvidence.citations().get(0)),
                Collections.emptyList());
        when(retrievalService.retrieve("发起合同有几种方式？"))
            .thenReturn(launchEvidence);
        when(retrievalService.retrieve("怎么把之前的纸质合同上传到点签里？"))
            .thenReturn(archiveEvidence);
        when(retrievalService.mergeWithProvidedContext(
                launchEvidence, archiveEvidence.context(), archiveEvidence.citations()))
            .thenReturn(mergedEvidence);
        when(aiModelService.chatWithModel(anyString(), anyString(), any()))
            .thenReturn(new ChatResponse(
                "如果合同尚未签署，可以上传文件发起签署；如果已经线下签完，可以扫描成PDF或图片后上传归档（仅支持pdf、ofd格式）。\n\n"
                    + "下一步：请回复“发起签署”或“纸质归档”。",
                true, "test-model", "test", 30, 20));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "ambiguous-contract-upload", question, "合同咨询");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(anyString(), systemPrompt.capture(), any());
        assertTrue(systemPrompt.getValue().contains("合同内容已经写好但尚未签署"));
        assertTrue(systemPrompt.getValue().contains("上传纸质合同扫描件归档"));
        assertTrue(systemPrompt.getValue().contains("不得默认客户指其中一个"));
        assertTrue(systemPrompt.getValue().contains("不得把未检索到的信息推断为平台不支持"));
        assertEquals(retrievalQuery, result.get("retrievalQuery"));
        assertEquals("CONTRACT_SIGNING_OPERATION",
            ((Map<?, ?>) result.get("nlpIntent")).get("intentCode"));
        assertTrue(((String) result.get("reply")).contains("发起签署”或“纸质归档"));
        assertEquals(false, ((String) result.get("reply")).contains("下一步"));
        assertTrue(((String) result.get("reply")).contains("PDF或OFD"));
        assertEquals(false, ((String) result.get("reply")).contains("PDF或图片"));
        verify(retrievalService).retrieve(
            "发起合同有几种方式？", KNOWLEDGE_RETRIEVAL_FILTERS, true);
        verify(retrievalService).retrieve(
            "怎么把之前的纸质合同上传到点签里？", KNOWLEDGE_RETRIEVAL_FILTERS, true);
    }

    @Test
    void keepsExactSignedAttachmentQuestionOnItsCanonicalKnowledgeAnswer() {
        String question = "合同签署完成后，发现附件漏传了，能补充上传附件吗？";
        String answer = "已完成签署的合同无法直接补充附件。可发起附件补充协议。";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, answer, answer, 1.0, "structured_qa_direct", false,
                List.of(citation("chunk:2398", "document", 50L, question)),
                Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "signed-attachment-exact", question, "合同咨询");

        assertEquals("knowledge_qa", result.get("source"));
        assertEquals(answer, result.get("reply"));
        assertEquals("structured_qa_direct",
            ((Map<?, ?>) result.get("retrieval")).get("decision"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(retrievalService).retrieve(question, KNOWLEDGE_RETRIEVAL_FILTERS, true);
        verify(retrievalService, never()).mergeWithProvidedContext(any(), anyString(), any());
    }

    @Test
    void answersSignedAttachmentFollowUpFromConversationHistoryWithGuardrail() {
        String question = "那漏掉的附件怎么办？";
        String standaloneQuery = "合同双方都已经签署完成了。 " + question;
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "合同双方都已经签署完成了。"),
            message("ai", "已完成签署的合同内容通常已经固定。"),
            message("user", question)));
        when(retrievalService.retrieve(standaloneQuery))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                true, false, null, "附件应通过补充协议处理。", 0.9,
                "rag", true, List.of(citation("chunk:3", "document", 3L, "附件")),
                Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "signed-attachment-follow-up", question, "合同咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertTrue(((String) result.get("reply")).contains("补充协议"));
        assertFalse(((String) result.get("reply")).contains("直接补充附件"));
        assertEquals(standaloneQuery, result.get("retrievalQuery"));
        assertEquals(true, result.get("queryRewritten"));
        assertEquals(false, result.get("retrievalHistoryUsed"));
        verify(retrievalService).retrieve(standaloneQuery);
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersMissingVerificationCodeWithReviewedLimitAndPasswordWorkaround() {
        String question = "验证码一直收不着，咋办？";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null,
                "检查手机信号，避免频繁发送。短时间频繁获取可能触发运营商限制，"
                    + "需在24小时后自动解除；可在账户内设置签约密码，先使用密码签约。",
                0.9,
                "rag", true, List.of(citation("chunk:5", "document", 5L, "验证码")),
                Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "verification-code", question, "账号咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals(false, result.get("needsTransfer"));
        assertTrue(((String) result.get("reply")).contains("手机信号"));
        assertTrue(((String) result.get("reply")).contains("频繁"));
        assertTrue(((String) result.get("reply")).contains("24小时"));
        assertTrue(((String) result.get("reply")).contains("签约密码"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersCompetitorComparisonWithoutUnsupportedSuperiorityClaims() {
        String question = "e签宝和点签到底哪个更安全、更有法律效力？";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "点签具备电子签名与存证能力。", 0.9,
                "rag", true, List.of(citation("chunk:4", "document", 4L, "安全合规")),
                Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "competitor-comparison", question, "产品咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertTrue(((String) result.get("reply")).contains("具体需求"));
        assertTrue(((String) result.get("reply")).contains("不作优劣判断"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void retainsQuantityKeywordsWhenIntentRewriteDropsThem() {
        String question = "批量发起合同支持多少份同时操作？";
        String rewritten = "点签 是否支持签署 合同";
        String answer = "支持同时发起10份";
        when(retrievalService.retrieve(
                eq(question), isNull(), isNull(), eq(KNOWLEDGE_RETRIEVAL_FILTERS),
                anyList(), eq(true)))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                true, true, answer, question + "\n" + answer, 1.0,
                "structured_qa_direct", false,
                List.of(citation("chunk:4192", "document", 4192L, question)),
                Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "batch-contract-limit", question, "合同咨询");

        assertEquals(answer, result.get("reply"));
        assertEquals("knowledge_qa", result.get("source"));
        assertEquals(question, result.get("retrievalPrimaryQuery"));
        assertEquals(List.of(question, rewritten), result.get("retrievalVariants"));
        assertEquals(rewritten, result.get("retrievalQuery"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QueryVariant>> variantsCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrievalService).retrieve(
            eq(question), isNull(), isNull(), eq(KNOWLEDGE_RETRIEVAL_FILTERS),
            variantsCaptor.capture(), eq(true));
        assertEquals(1, variantsCaptor.getValue().size());
        assertEquals(rewritten, variantsCaptor.getValue().get(0).query());
        assertEquals(0.85, variantsCaptor.getValue().get(0).weight());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void keepsContractLaunchMethodsSeparateFromServiceModes() {
        String question = "发起合同有几种方式？";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null,
                "发起合同可以上传文件或使用模板。服务模式包括 SaaS、OpenAPI 和定制化开发。",
                0.93, "answered", false,
                List.of(citation("chunk:2", "document", 2L, "合同发起方式")),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), any()))
            .thenReturn(new ChatResponse(
                "发起合同主要有两种方式：\n\n1. 上传文件发起：填写完成后直接上传合同文件。\n"
                    + "2. 模板发起：在 PC 端上传企业模板并设置签署区域后发起。",
                true, "test-model", "test", 20, 30));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "dingtalk", "contract-launch-user", question, "咨询");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(anyString(), systemPrompt.capture(), any());
        assertTrue(systemPrompt.getValue().contains("发起合同主要有两种方式"));
        assertTrue(systemPrompt.getValue().contains("平台通用模板属于模板发起"));
        assertTrue(systemPrompt.getValue().contains("SaaS、OpenAPI、定制化开发属于服务模式"));
        assertTrue(((String) result.get("reply")).contains("主要有两种方式"));
    }

    @Test
    void pausesAiWhileHumanAgentOwnsConversation() {
        BotConversation transferred = new BotConversation();
        transferred.setId(10L);
        transferred.setStatus("transferred");
        transferred.setHandoffStatus("PROCESSING");
        transferred.setAssignedAgentId(9L);
        transferred.setAssignedAgentName("客服小李");
        when(conversationService.getOrCreate(anyString(), anyString(), anyString()))
            .thenReturn(transferred);

        Map<String, Object> result = dialogService.send(
            "web", "human-session", "我再补充一个问题", "人工会话");

        assertEquals("human_handoff", result.get("source"));
        assertEquals("human_handling", result.get("answerStatus"));
        assertEquals(true, result.get("humanHandling"));
        assertEquals(9L, result.get("assignedAgentId"));
        verify(safetyService, never()).checkUserInput(anyString());
        verify(businessToolOrchestrator, never()).route(any(), anyString(), anyString(),
            anyString(), any());
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void answersPreviousQuestionWhileWaitingForHuman() {
        BotConversation transferred = new BotConversation();
        transferred.setId(10L);
        transferred.setStatus("transferred");
        transferred.setHandoffStatus("WAITING");
        when(conversationService.getOrCreate(anyString(), anyString(), anyString()))
            .thenReturn(transferred);
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "有没有个人套餐价格表"),
            message("user", "我刚才问你什么问题了")));

        Map<String, Object> result = dialogService.send(
            "dingtalk", "waiting-session", "我刚才问你什么问题了", "人工会话");

        assertEquals("handoff_waiting", result.get("source"));
        assertEquals("handoff_context", result.get("answerStatus"));
        assertTrue(((String) result.get("reply")).contains("个人套餐价格表"));
        verify(handoffCoordinator).recordUserMessage(10L, "我刚才问你什么问题了");
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersPreviousQuestionInNormalConversationWithoutRag() {
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "点签怎么使用？"),
            message("ai", "可以通过微信、PC网页端和钉钉使用。"),
            message("user", "我上面问你什么问题了？")));

        Map<String, Object> result = dialogService.send(
            "playground", "normal-session", "我上面问你什么问题了？", "试聊");

        assertEquals("conversation_context", result.get("source"));
        assertEquals("previous_question", result.get("fallbackDecision"));
        assertEquals("answered", result.get("answerStatus"));
        assertTrue(((String) result.get("reply")).contains("点签怎么使用？"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void recognizesPreviousQuestionAliasInNormalConversation() {
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "企业认证怎么认证？"),
            message("ai", "企业认证有三种方式。"),
            message("user", "上一个问题是什么？")));

        Map<String, Object> result = dialogService.send(
            "playground", "normal-session", "上一个问题是什么？", "试聊");

        assertEquals("conversation_context", result.get("source"));
        assertTrue(((String) result.get("reply")).contains("企业认证怎么认证？"));
        verify(retrievalService, never()).retrieve(anyString());
    }

    @Test
    void sendsNewQuestionWithMediaCaptionThroughNormalAnsweringPipeline() {
        BotConversation transferred = new BotConversation();
        transferred.setId(10L);
        transferred.setStatus("transferred");
        transferred.setHandoffStatus("WAITING");
        when(conversationService.getOrCreate(anyString(), anyString(), anyString()))
            .thenReturn(transferred);
        when(retrievalService.retrieve(anyString())).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "【参考知识】操作步骤", 0.92, "answered", false,
                Collections.emptyList(), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), any()))
            .thenReturn(new ChatResponse("请先打开设置页。", true,
                "test-model", "test", 10, 6));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "dingtalk", "waiting-session",
            "[客户附带问题]\n这个怎么操作\n\n"
                + "[客户发送了一张图片，以下为图片中的文字]\n手机号：13800138000",
            "人工会话");

        assertEquals("rag_ai", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("请先打开设置页。", result.get("reply"));
        verify(retrievalService).retrieve(anyString());
    }

    @Test
    void recognizesOnlyExplicitFieldValueAsHandoffSupplement() {
        BotConversation transferred = new BotConversation();
        transferred.setId(10L);
        transferred.setStatus("transferred");
        transferred.setHandoffStatus("WAITING");
        when(conversationService.getOrCreate(anyString(), anyString(), anyString()))
            .thenReturn(transferred);

        Map<String, Object> result = dialogService.send(
            "dingtalk", "waiting-session", "公司名称是翔晟", "人工会话");

        assertEquals("handoff_waiting", result.get("source"));
        assertEquals("handoff_supplement", result.get("answerStatus"));
        verify(retrievalService, never()).retrieve(anyString());
    }

    @Test
    void recognizesRedactedPhoneAsExplicitHandoffSupplement() {
        BotConversation transferred = new BotConversation();
        transferred.setId(10L);
        transferred.setStatus("transferred");
        transferred.setHandoffStatus("WAITING");
        when(conversationService.getOrCreate(anyString(), anyString(), anyString()))
            .thenReturn(transferred);

        Map<String, Object> result = dialogService.send(
            "dingtalk", "waiting-session", "手机号 13800138000", "人工会话");

        assertEquals("handoff_supplement", result.get("answerStatus"));
        assertEquals(true, result.get("redactionApplied"));
        verify(retrievalService, never()).retrieve(anyString());
    }

    @Test
    void doesNotTreatOcrFieldLabelAsCustomerHandoffSupplement() {
        BotConversation transferred = new BotConversation();
        transferred.setId(10L);
        transferred.setStatus("transferred");
        transferred.setHandoffStatus("WAITING");
        when(conversationService.getOrCreate(anyString(), anyString(), anyString()))
            .thenReturn(transferred);

        Map<String, Object> result = dialogService.send(
            "dingtalk", "waiting-session",
            "[客户发送了一张图片，以下为图片中的文字]\n手机号：13800138000",
            "人工会话");

        assertEquals("no_answer", result.get("source"));
        assertEquals(false, "handoff_supplement".equals(result.get("answerStatus")));
        verify(retrievalService).retrieve(anyString());
    }

    @Test
    void cancelsQueuedHandoffWhenCustomerNoLongerNeedsHelp() {
        BotConversation transferred = new BotConversation();
        transferred.setId(10L);
        transferred.setStatus("transferred");
        transferred.setHandoffStatus("WAITING");
        when(conversationService.getOrCreate(anyString(), anyString(), anyString()))
            .thenReturn(transferred);
        when(handoffCoordinator.cancelWaitingHandoff(10L, "用户取消等待人工客服"))
            .thenReturn(true);

        Map<String, Object> result = dialogService.send(
            "dingtalk", "waiting-session", "算了，不用了", "人工会话");

        assertEquals("handoff_waiting", result.get("source"));
        assertEquals("handoff_cancelled", result.get("answerStatus"));
        assertEquals("CANCELLED", result.get("handoffStatus"));
        assertEquals(false, result.get("needsTransfer"));
        verify(handoffCoordinator).recordUserMessage(10L, "算了，不用了");
        verify(retrievalService, never()).retrieve(anyString());
    }

    @Test
    void abstainsAndRecordsQuestionWhenKnowledgeIsInsufficient() {
        when(retrievalService.retrieve("火星办公室几点开门"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.18, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "user-1", "火星办公室几点开门", "咨询");

        assertEquals("no_answer", result.get("source"));
        assertEquals("no_answer", result.get("answerStatus"));
        assertEquals(SCOPE_FALLBACK_REPLY, result.get("reply"));
        assertEquals(Collections.emptyList(), result.get("citations"));
        assertEquals(List.of("NO_ANSWER"), result.get("badCaseTriggers"));
        verify(unmatchedQuestionService).recordBadCase(
            eq("火星办公室几点开门"), eq(Set.of("NO_ANSWER")), any());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersGeneralQuestionWithRestrictedNativeFallbackWhenKnowledgeIsMissing() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        when(retrievalService.retrieve("什么是电子签名"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.18, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("native-system-prompt")
                    && prompt.contains("强制事实与安全边界")), eq(null)))
            .thenReturn(new ChatResponse("电子签名是用于确认签署人身份和表达签署意愿的电子形式签名。",
                true, "test-model", "test", 18, 12));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-native", "什么是电子签名", "咨询");

        assertEquals("native_ai", result.get("source"));
        assertEquals("native", result.get("answerMode"));
        assertEquals("native", result.get("fallbackDecision"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals(false, result.get("ragSource"));
        assertEquals(0, result.get("ragContextChars"));
        assertEquals(false, result.get("needsTransfer"));
        assertEquals(Collections.emptyList(), result.get("citations"));
        assertEquals(Collections.emptyList(), result.get("badCaseTriggers"));
        verify(unmatchedQuestionService, never()).recordBadCase(anyString(), any(), any());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(
            promptCaptor.capture(), argThat(prompt -> prompt.startsWith("native-system-prompt")
                && prompt.contains("强制事实与安全边界")), eq(null));
        assertTrue(promptCaptor.getValue().contains("属于电子合同"));
        assertTrue(promptCaptor.getValue().contains("按问题复杂度选择最小充分结构"));
        assertTrue(promptCaptor.getValue().contains("不得为凑结构而编造"));
        ArgumentCaptor<com.feisheng.bot.core.entity.BotAiReplyLog> logCaptor =
            ArgumentCaptor.forClass(com.feisheng.bot.core.entity.BotAiReplyLog.class);
        verify(aiReplyLogMapper).insert(logCaptor.capture());
        assertTrue(logCaptor.getValue().getPrompt().contains("【当前问题】\n什么是电子签名"));
        assertEquals(true, result.get("promptApplied"));
        assertEquals("native_fallback", result.get("promptPath"));
        assertEquals("configured_native", result.get("promptSource"));
        assertTrue(logCaptor.getValue().getTraceJson()
            .contains("\"promptPath\":\"native_fallback\""));
    }

    @Test
    void rejectsOutOfDomainQuestionBeforeNativeFallbackModelCall() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        String question = "教我做一份椰子鸡火锅";
        when(retrievalService.retrieve(question))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.164, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "native-out-of-domain", question, "咨询");

        assertEquals(SCOPE_FALLBACK_REPLY, result.get("reply"));
        assertEquals("out_of_scope", result.get("source"));
        assertEquals("out_of_scope", result.get("answerStatus"));
        assertEquals("out_of_scope", result.get("fallbackDecision"));
        assertEquals("NO_KNOWLEDGE", result.get("answerDecision"));
        assertEquals(false, result.get("needsTransfer"));
        assertEquals(false, result.get("promptApplied"));
        assertEquals(Collections.emptyList(), result.get("citations"));
        verify(retrievalService).retrieve(question);
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(unmatchedQuestionService, never()).record(anyString());
    }

    @Test
    void rejectsWeakBusinessTermAndCreativeCollisionsBeforeNativeFallback() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        List<String> questions = List.of(
            "员工食堂有什么菜谱",
            "用微信做椰子鸡",
            "下载一首歌曲",
            "怎么登录游戏账号",
            "写一首关于电子合同的诗");
        for (String question : questions) {
            when(retrievalService.retrieve(question))
                .thenReturn(new RagRetrievalService.RetrievalResult(
                    false, false, null, null, 0.1, "no_answer", true,
                    Collections.emptyList(), Collections.emptyList()));

            Map<String, Object> result = dialogService.send(
                "web", "native-collision-" + question.hashCode(), question, "咨询");

            assertEquals("out_of_scope", result.get("source"), question);
            assertEquals("NO_KNOWLEDGE", result.get("answerDecision"), question);
            assertEquals(false, result.get("needsTransfer"), question);
            assertEquals(false, result.get("promptApplied"), question);
        }

        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(unmatchedQuestionService, never()).record(anyString());
    }

    @Test
    void doesNotTreatConversationHistoryAsPermissionForUnrelatedNativeFallback() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        String question = "这个椰子鸡怎么做";
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "点签电子合同怎么签？"),
            message("ai", "可以在线发起和签署合同。"),
            message("user", question)));
        Map<String, Object> result = dialogService.send(
            "web", "native-history-collision", question, "咨询");

        assertEquals("out_of_scope", result.get("source"));
        assertEquals("NO_KNOWLEDGE", result.get("answerDecision"));
        assertEquals(false, result.get("promptApplied"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void keepsSpecificContractCapabilityInBusinessScopeDespiteMovieTerm() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        String question = "电影投资合同能在点签签吗";
        when(retrievalService.retrieve(anyString()))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.1, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "native-contract-scope", question, "咨询");

        assertEquals("no_answer", result.get("source"));
        assertEquals("contract_capability_no_evidence", result.get("fallbackDecision"));
        assertEquals("NO_KNOWLEDGE", result.get("answerDecision"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void marksNativeFallbackAsBuiltInWhenNoNativePromptIsConfigured() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        ReflectionTestUtils.setField(dialogService, "nativeFallbackSystemPrompt", "");
        when(retrievalService.retrieve("什么是电子签名"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.18, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("你是点签电子合同官方客服的受限兜底助手")
                    && prompt.contains("强制事实与安全边界")
                    && prompt.contains("不得回答天气、菜谱")), eq(null)))
            .thenReturn(new ChatResponse("电子签名是用于确认签署人身份和表达签署意愿的电子形式签名。",
                true, "test-model", "test", 18, 12));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-native-built-in", "什么是电子签名", "咨询");

        assertEquals("built_in_native", result.get("promptSource"));
        assertEquals(true, result.get("promptApplied"));
    }

    @Test
    void retriesAnswerableLowConfidenceRagWithTheSameEvidence() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        String question = "合同正文写错了，对方还没签，现在怎么处理";
        Map<String, Object> evidenceCitation =
            citation("doc:1", "document", 1L, "合同正文修改");
        when(retrievalService.retrieve(question))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                true, false, null,
                "合同发起后对方未签署时，需先撤回，修改正文后重新发起。", 0.50,
                "rag", true, List.of(evidenceCitation),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")), isNull()))
            .thenReturn(
                new ChatResponse("__NO_ANSWER__", true,
                    "test-model", "test", 20, 3),
                new ChatResponse("不能直接修改。请先撤回合同，修改正文后重新发起。", true,
                    "test-model", "test", 24, 12));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "rag-evidence-retry", question, "咨询");

        assertEquals("rag_ai", result.get("source"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals("evidence_answer_retry", result.get("fallbackDecision"));
        assertEquals(List.of(evidenceCitation), result.get("citations"));
        assertEquals(true, result.get("lowConfidence"));
        assertEquals(true, result.get("needsTransfer"));
        assertEquals(2, result.get("modelInvocationCount"));
        assertEquals(2, ((List<?>) result.get("promptInvocations")).size());
        assertEquals("rag", ((Map<?, ?>) ((List<?>) result.get("promptInvocations")).get(0))
            .get("path"));
        assertEquals("rag",
            ((Map<?, ?>) ((List<?>) result.get("promptInvocations")).get(1)).get("path"));
        ArgumentCaptor<com.feisheng.bot.core.entity.BotAiReplyLog> logCaptor =
            ArgumentCaptor.forClass(com.feisheng.bot.core.entity.BotAiReplyLog.class);
        verify(aiReplyLogMapper).insert(logCaptor.capture());
        assertEquals(true, logCaptor.getValue().getRagUsed());
        assertEquals(44, logCaptor.getValue().getTokensInput());
        assertEquals(15, logCaptor.getValue().getTokensOutput());
        verify(aiModelService, times(2)).chatWithModel(anyString(), argThat(prompt ->
            prompt.startsWith("rag-system-prompt")), isNull());
    }

    @Test
    void returnsSingleReviewedAnswerWhenNegativeCapabilityHasAnAlternativePath() {
        String question = "点签支持手机APP吗？";
        String answer = "点签目前不提供独立手机 APP；手机用户可以通过微信小程序或短信签署链接办理相关操作。";
        String context = """
            【企业内部事实】
            事实：点签可以在哪里使用？
            问题：点签可以在哪里使用？
            答案：%s
            回答时先锁定客户明确询问的产品、业务或对象。
            """.formatted(answer);
        Map<String, Object> evidenceCitation =
            citation("chunk:5514", "document", 5514L, "点签可以在哪里使用？");
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, context, 0.83, "rag", true,
                List.of(evidenceCitation), Collections.emptyList()));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "negative-capability-alternative", question, "咨询");

        assertEquals(answer, result.get("reply"));
        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("evidence_consistency_guardrail", result.get("fallbackDecision"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals(List.of(evidenceCitation), result.get("citations"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void abstainsAfterTwoEvidenceBackedRagRefusalsWithoutCallingNative() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        String question = "合同正文写错了，对方还没签，现在怎么处理";
        when(retrievalService.retrieve(question))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                true, false, null,
                "合同发起后对方未签署时，需先撤回，修改正文后重新发起。", 0.82,
                "rag", true,
                List.of(citation("doc:1", "document", 1L, "合同正文修改")),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")), isNull()))
            .thenReturn(
                new ChatResponse("__NO_ANSWER__", true,
                    "test-model", "test", 20, 3),
                new ChatResponse("__NO_ANSWER__", true,
                    "test-model", "test", 22, 3));

        Map<String, Object> result = dialogService.send(
            "web", "rag-evidence-refused", question, "咨询");

        assertEquals("no_answer", result.get("source"));
        assertEquals("no_answer", result.get("answerStatus"));
        assertEquals("NO_KNOWLEDGE", result.get("answerDecision"));
        assertEquals("rag_abstained_after_retry", result.get("fallbackDecision"));
        assertEquals(Collections.emptyList(), result.get("citations"));
        assertEquals(false, result.get("needsTransfer"));
        assertEquals(2, result.get("modelInvocationCount"));
        assertEquals(List.of("rag", "rag"),
            ((List<?>) result.get("promptInvocations")).stream()
                .map(item -> ((Map<?, ?>) item).get("path"))
                .toList());
        verify(aiModelService, times(2)).chatWithModel(anyString(), argThat(prompt ->
            prompt.startsWith("rag-system-prompt")), isNull());
        assertEquals(List.of("NO_ANSWER"), result.get("badCaseTriggers"));
        verify(unmatchedQuestionService).recordBadCase(
            eq(question), eq(Set.of("NO_ANSWER")), any());
    }

    @Test
    void recordsMisplacedDecisionSignalWithPathAndAttempt() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        when(retrievalService.retrieve("什么是电子签名"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.18, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(new ChatResponse("稳定答案中不应出现 __NO_ANSWER__ 标记。",
                true, "test-model", "test", 18, 12));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "protocol-violation", "什么是电子签名", "咨询");

        assertEquals("稳定答案中不应出现 标记。", result.get("reply"));
        assertEquals(List.of("decision_signal_not_on_first_line"),
            result.get("modelProtocolViolations"));
        Map<?, ?> detail = (Map<?, ?>) ((List<?>) result.get("modelProtocolViolationDetails"))
            .get(0);
        assertEquals("native_fallback", detail.get("path"));
        assertEquals(1, detail.get("attempt"));
    }

    @Test
    void asksForClarificationInsteadOfGuessingAmbiguousQuestion() {
        Map<String, Object> result = dialogService.send(
            "web", "user-clarify", "这个怎么操作", "咨询");

        assertEquals("clarify", result.get("source"));
        assertEquals("clarify", result.get("answerStatus"));
        assertEquals("clarify", result.get("answerMode"));
        assertEquals("CLARIFY", result.get("answerDecision"));
        assertEquals("unresolved_reference", result.get("fallbackDecision"));
        assertEquals("请补充具体场景", result.get("reply"));
        assertEquals(false, result.get("needsTransfer"));
        assertEquals("CLARIFY", ((Map<?, ?>) result.get("understanding")).get("decision"));
        assertEquals("unresolved_reference",
            ((Map<?, ?>) result.get("understanding")).get("reason"));
        assertEquals("context",
            ((Map<?, ?>) result.get("pendingClarification")).get("missingSlot"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(businessToolOrchestrator, never()).route(
            any(), anyString(), anyString(), anyString(), anyList());
        verify(intentService, never()).match(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(unmatchedQuestionService, never()).record(anyString());
    }

    @Test
    void usesSemanticUnderstandingBeforeClarifyingUnresolvedContext() {
        String rewritten = "点签企业账号如何登录？";
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "成员入口在哪里"),
            message("ai", "请说明您使用的产品和页面"),
            message("user", "这个怎么操作")));
        when(intentUnderstandingService.understand(
                eq("这个怎么操作"), anyList(), isNull()))
            .thenReturn(knowledgeUnderstanding("ACCOUNT_OPERATION", rewritten, true));
        when(retrievalService.retrieve(rewritten)).thenReturn(
            retrieval("企业账号可从点签登录页进入。", 0.90));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(new ChatResponse(
                "企业账号可从点签登录页进入。", true,
                "answer-model", "test", 30, 12));

        Map<String, Object> result = dialogService.send(
            "web", "semantic-context", "这个怎么操作", "咨询");

        assertEquals("rag_ai", result.get("source"));
        assertEquals(rewritten, result.get("retrievalPrimaryQuery"));
        assertEquals("semantic_context_resolution",
            result.get("clarificationResolutionSource"));
        Map<?, ?> understanding = (Map<?, ?>) result.get("intentUnderstanding");
        assertEquals("KNOWLEDGE", understanding.get("route"));
        assertEquals("ACCOUNT_OPERATION", understanding.get("intentCode"));
        assertEquals(0.91, understanding.get("confidence"));
        verify(retrievalService).retrieve(
            eq(rewritten), isNull(), isNull(), eq(KNOWLEDGE_RETRIEVAL_FILTERS),
            argThat(variants -> variants.stream().anyMatch(variant ->
                "semantic_intent_entities".equals(variant.purpose())
                    && variant.query().contains("账号注册"))), eq(true));
    }

    @Test
    void asksTargetedSemanticClarificationAndPersistsTheMissingSlot() {
        String question = "这个怎么操作";
        when(intentUnderstandingService.understand(eq(question), anyList(), isNull()))
            .thenReturn(new IntentUnderstandingService.Understanding(
                true, true, IntentUnderstandingService.Route.CLARIFY,
                "UNKNOWN", "", Map.of("operation", "登录"),
                List.of("user_type"), true, 0.90, "semantic_understanding",
                "intent-model", "test", 20, 8, 10L));

        Map<String, Object> result = dialogService.send(
            "web", "semantic-clarify", question, "咨询");

        assertEquals("decision", result.get("source"));
        assertEquals("CLARIFY", result.get("answerDecision"));
        assertEquals("missing_user_type", result.get("fallbackDecision"));
        assertEquals("请问您使用的是个人账号还是企业账号？", result.get("reply"));
        assertEquals("userType",
            ((Map<?, ?>) result.get("pendingClarification")).get("missingSlot"));
        assertEquals("CLARIFY",
            ((Map<?, ?>) result.get("intentUnderstanding")).get("route"));
        verify(retrievalService, never()).retrieve(anyString());
    }

    @Test
    void routesSemanticHistoryRecallWithoutKnowledgeRetrievalOrEvidenceRetry() {
        String question = "我忘记我之前是哪个认证了？";
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "您好，我是海南飞晟科技有限公司的，怎么完成企业认证？"),
            message("ai", "企业认证有法人认证、授权书认证和对公打款认证三种方式。"),
            message("user", question)));
        when(intentUnderstandingService.understand(eq(question), anyList(), isNull()))
            .thenReturn(new IntentUnderstandingService.Understanding(
                true, true, IntentUnderstandingService.Route.KNOWLEDGE,
                "HISTORY_RECALL", "企业认证", Map.of(), List.of(), true,
                0.96, "semantic_understanding", "intent-model", "test", 20, 8, 12L));

        Map<String, Object> result = dialogService.send(
            "dingtalk", "history-recall", question, "咨询");

        verify(intentUnderstandingService).understand(eq(question), anyList(), isNull());
        assertEquals("conversation_context", result.get("source"));
        assertEquals("history_recall", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("企业认证"));
        assertTrue(((String) result.get("reply")).contains("没有记录"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void retriesIndependentUnknownQuestionWithSemanticStandaloneQuery() {
        String question = "适合哪些团队使用";
        String rewritten = "点签电子合同适合哪些团队使用？";
        when(intentUnderstandingService.understand(eq(question), anyList(), isNull()))
            .thenReturn(knowledgeUnderstanding("OTHER_KNOWLEDGE", rewritten, false));
        when(retrievalService.retrieve(rewritten)).thenReturn(
            retrieval("点签电子合同适用于需要线上签约的企业团队。", 0.86));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(new ChatResponse(
                "点签电子合同适用于需要线上签约的企业团队。", true,
                "answer-model", "test", 30, 12));

        Map<String, Object> result = dialogService.send(
            "web", "semantic-retry", question, "咨询");

        assertEquals("rag_ai", result.get("source"));
        assertEquals(rewritten, result.get("retrievalPrimaryQuery"));
        assertEquals(true, result.get("queryRewritten"));
        assertEquals("semantic_retrieval_retry",
            result.get("clarificationResolutionSource"));
        verify(retrievalService).retrieve(
            question, KNOWLEDGE_RETRIEVAL_FILTERS, true);
        verify(retrievalService).retrieve(
            rewritten, KNOWLEDGE_RETRIEVAL_FILTERS, true);
        verify(intentUnderstandingService).understand(
            eq(question), anyList(), isNull());
    }

    @Test
    void bypassesToolsAndStaticIntentRepliesWhenKnowledgeOnlyModeIsEnabled() {
        ReflectionTestUtils.setField(dialogService, "businessToolsEnabled", false);
        ReflectionTestUtils.setField(dialogService, "intentStaticRepliesEnabled", false);
        String question = "退款进度怎么查";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, "请在订单页面查看退款进度。", null, 1.0,
                "direct", true, Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "knowledge-only", question, "咨询");

        assertEquals("faq", result.get("source"));
        assertEquals("请在订单页面查看退款进度。", result.get("reply"));
        verify(intentService, never()).match(anyString());
        verify(businessToolOrchestrator, never()).route(
            any(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void letsTheModelHandleACompleteQuestionInsteadOfUsingGenericClarification() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        String question = "点签有什么培训安排吗";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.0, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("native-system-prompt")
                    && prompt.contains("强制事实与安全边界")), eq(null)))
            .thenReturn(new ChatResponse(
                "__ANSWER_PARTIAL__\n产品培训通常需要结合使用角色和功能范围安排；点签的具体培训计划需要进一步确认。请问您需要管理员培训还是普通签署人培训？",
                true, "test-model", "test", 24, 22));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-training", question, "咨询");

        assertEquals("native_ai", result.get("source"));
        assertEquals("ANSWER_PARTIAL", result.get("answerDecision"));
        assertEquals("partial", result.get("answerMode"));
        assertTrue(((String) result.get("reply")).contains("管理员培训"));
        assertEquals(false, ((String) result.get("reply")).contains("__ANSWER_PARTIAL__"));
    }

    @Test
    void handsOffCustomQuoteQuestionBeforeKnowledgeRetrieval() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);

        Map<String, Object> result = dialogService.send(
            "web", "user-high-risk", "能给我们一个定制报价和折扣吗", "咨询");

        assertEquals("price_handoff", result.get("source"));
        assertEquals("price_handoff", result.get("answerStatus"));
        assertEquals("restricted", result.get("answerMode"));
        assertEquals("HANDOFF", result.get("answerDecision"));
        assertEquals("price_handoff", result.get("fallbackDecision"));
        assertEquals("价格问题已提交人工确认", result.get("reply"));
        assertEquals(true, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(unmatchedQuestionService, never()).record(anyString());
        verify(handoffCoordinator).handoff(
            10L, "价格相关信息需要人工确认", "P1");
    }

    @Test
    void usesUnifiedReplyForContractPricingQuestion() {
        Map<String, Object> result = dialogService.send(
            "web", "user-price-defaults", "点签电子合同怎么收费？", "咨询");

        assertEquals("price_qualification", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals("unified_contract_pricing", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("https://www.fs-signature.com/"));
        assertTrue(((String) result.get("reply")).contains("186 8963 3999"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void suppressesEveryReplyWhileHumanAgentIsHandlingConversation() {
        BotConversation transferred = new BotConversation();
        transferred.setId(10L);
        transferred.setStatus("transferred");
        transferred.setHandoffStatus("PROCESSING");
        transferred.setAssignedAgentId(9L);
        transferred.setAssignedAgentName("客服小李");
        when(conversationService.getOrCreate(anyString(), anyString(), anyString()))
            .thenReturn(transferred);

        when(messageService.getByConversation(10L)).thenReturn(Collections.emptyList());

        Map<String, Object> first = dialogService.send(
            "dingtalk", "human-session-once", "第一条补充信息", "人工会话");
        Map<String, Object> second = dialogService.send(
            "dingtalk", "human-session-once", "第二条补充信息", "人工会话");

        assertEquals("", first.get("reply"));
        assertEquals(true, first.get("suppressReply"));
        assertEquals("", second.get("reply"));
        assertEquals(true, second.get("suppressReply"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void suppressesGeneratedAiReplyWhenHumanClaimsConversationDuringGeneration() {
        String context = "【参考知识库内容】\n问题：如何操作\n答案：点击提交。";
        when(retrievalService.citationsForProvidedContext(context)).thenReturn(List.of(
            citation("provided:1", "provided_context", null, "调用方上下文")));
        when(aiModelService.chatWithModel(anyString(), anyString(), any()))
            .thenReturn(new ChatResponse("点击提交。", true,
                "test-model", "test", 20, 8));
        BotConversation transferred = new BotConversation();
        transferred.setId(10L);
        transferred.setStatus("transferred");
        transferred.setHandoffStatus("PROCESSING");
        transferred.setAssignedAgentId(9L);
        transferred.setAssignedAgentName("客服小李");
        when(conversationService.getById(10L)).thenReturn(transferred);

        Map<String, Object> result = dialogService.send(
            "dingtalk", "human-race", "这个怎么操作", "咨询", context);

        assertEquals("", result.get("reply"));
        assertEquals(true, result.get("suppressReply"));
        ArgumentCaptor<BotMessage> messageCaptor = ArgumentCaptor.forClass(BotMessage.class);
        verify(messageService).save(messageCaptor.capture());
        assertEquals("user", messageCaptor.getValue().getRole());
        verify(handoffCoordinator).recordUserMessage(10L, "这个怎么操作");
    }

    @Test
    void usesUnifiedReplyForPerContractPriceFollowUp() {
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "一千份合同多少钱？"),
            message("ai", "请问贵公司一年的签署量大约在多少呢？"),
            message("user", "多少钱一份")));

        Map<String, Object> result = dialogService.send(
            "web", "user-price-follow-up", "多少钱一份", "咨询");

        assertEquals("price_qualification", result.get("source"));
        assertEquals("unified_contract_pricing", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("合同套餐和每份合同的价格"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void usesUnifiedReplyForOfficialWebsiteQuestion() {
        Map<String, Object> result = dialogService.send(
            "web", "user-official-website", "点签官网地址是什么？", "咨询");

        assertEquals("basic_conversation", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals("basic_official_website", result.get("fallbackDecision"));
        assertEquals("您好！我们公司的官网地址是：https://www.fs-signature.com/。",
            result.get("reply"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void clarifiesGenericCaProductBeforeRetrieval() {
        Map<String, Object> result = dialogService.send(
            "web", "user-ca-clarification", "CA证书怎么申请？", "咨询");

        assertEquals("basic_conversation", result.get("source"));
        assertEquals("basic_ca_product_clarification", result.get("fallbackDecision"));
        assertEquals("您是咨询翔晟CA吗还是点签电子合同平台呢？", result.get("reply"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void clarifiesGenericUKeyProductBeforeRetrieval() {
        Map<String, Object> result = dialogService.send(
            "web", "user-ukey-clarification", "U-Key怎么用？", "咨询");

        assertEquals("basic_conversation", result.get("source"));
        assertEquals("basic_ukey_product_clarification", result.get("fallbackDecision"));
        assertEquals("您是咨询翔晟UKey吗？还是点签电子合同平台呢？", result.get("reply"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void routesExplicitXiangshengProductToServiceGroup() {
        Map<String, Object> result = dialogService.send(
            "web", "user-xiangsheng-ukey", "翔晟UKey怎么申请？", "咨询");

        assertEquals("out_of_scope", result.get("source"));
        assertEquals("parent_company_out_of_scope", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("575467556"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void givesUKeyLimitReplyWhenCustomerSelectsDianqian() {
        Map<String, Object> result = dialogService.send(
            "web", "user-dianqian-ukey", "点签电子合同的UKey怎么用？", "咨询");

        assertEquals("basic_conversation", result.get("source"));
        assertEquals("basic_ukey_dianqian_limit", result.get("fallbackDecision"));
        assertEquals("您好，这个问题我不能准确地回答，需要我帮您转接人工吗？",
            result.get("reply"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void usesUnifiedReplyForSpecificMembershipPriceQuestion() {
        String question = "点签的会员价是多少钱？";

        Map<String, Object> result = dialogService.send(
            "web", "user-membership-price", question, "咨询");

        assertEquals("price_qualification", result.get("source"));
        assertEquals("unified_contract_pricing", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("https://www.fs-signature.com/"));
        assertTrue(((String) result.get("reply")).contains("186 8963 3999"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void leavesAnnualVolumeQualificationWhenUserChangesTopic() {
        String question = "因信息问题产生的损失，由谁承担？";
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "点签电子合同怎么收费？"),
            message("ai", "请问贵公司一年的签署量大约在多少呢？"),
            message("user", question)));
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.3, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "user-new-topic-after-price", question, "咨询");

        verify(retrievalService).retrieve(question);
        assertFalse("price_qualification".equals(result.get("source")));
        assertFalse("请问贵公司一年的签署量大约在多少呢？".equals(result.get("reply")));
    }

    @Test
    void usesOfficialPackagesWhenAnnualSigningVolumeIsBelowOneThousand() {
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "点签电子合同怎么收费？"),
            message("ai", "请问贵公司一年的签署量大约在多少呢？"),
            message("user", "1000以下")));

        Map<String, Object> result = dialogService.send(
            "web", "price-standard-volume", "1000以下", "咨询");

        assertEquals("price_qualification", result.get("source"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals("standard_package_pricing", result.get("fallbackDecision"));
        assertEquals("目前我们的价格以官网标准套餐价格为准，可按需求选择相应的合同套餐份数进行购买。",
            result.get("reply"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void offersCustomerManagerAtTheOneThousandBoundaryWithoutPrematureHandoff() {
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "点签电子合同怎么收费？"),
            message("ai", "请问贵公司一年的签署量大约在多少呢？"),
            message("user", "一年大约1000份")));

        Map<String, Object> result = dialogService.send(
            "web", "price-enterprise-volume", "一年大约1000份", "咨询");

        assertEquals("price_qualification", result.get("source"));
        assertEquals("CLARIFY", result.get("answerDecision"));
        assertEquals("enterprise_pricing_handoff_offered", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("客户经理对接"));
        assertTrue(((String) result.get("reply")).contains("需要我帮您转人工吗"));
        assertEquals(false, result.get("needsTransfer"));
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void createsHandoffOnlyAfterEnterpriseCustomerAcceptsTheOffer() {
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "一年大约2000份"),
            message("ai", "这边给您安排客户经理对接，根据您的合同签署量、使用年限及功能需求，"
                + "定制性价比更高的服务方案。需要我帮您转人工吗？"),
            message("user", "需要")));

        Map<String, Object> result = dialogService.send(
            "web", "price-handoff-consent", "需要", "咨询");

        assertEquals("handoff", result.get("source"));
        assertEquals("HANDOFF", result.get("answerDecision"));
        assertEquals(true, result.get("needsTransfer"));
        verify(handoffCoordinator).handoff(10L, "客户主动请求人工客服", "P1");
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void handsOffBeforeGenerationWhenMinimumUnitPriceEvidenceConflicts() {
        String question = "这两段收费说明哪个准确？";
        String context = "电子合同签署单价最低仅需3元/份。"
            + "正式使用时按签署份数计费，如单份低至5元。";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, context, 0.93, "rag", true,
                List.of(citation("chunk:price", "document", 1L, "收费标准")),
                Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "conflicting-price-evidence", question, "咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("evidence_conflict", result.get("answerStatus"));
        assertEquals("HANDOFF", result.get("answerDecision"));
        assertEquals("conflicting_scalar_facts", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("价格口径存在冲突"));
        assertEquals(true, result.get("needsTransfer"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator).handoff(10L, "可核实事实口径冲突", "P1");
    }

    @Test
    void replacesUnsupportedMaterialListBeforeItIsShownToTheCustomer() {
        String question = "企业实名认证需要准备什么材料？";
        String context = "企业用户需提交营业执照等资质信息。"
            + "企业认证可通过法人在线认证、法人远程授权或对公打款完成。";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, context, 0.82, "rag", true,
                List.of(citation("chunk:auth", "document", 2L, "企业认证")),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(new ChatResponse(
                "企业认证需要准备：\n1. 营业执照副本复印件；\n"
                    + "2. 法人身份证正反面复印件；\n3. 组织机构代码证复印件；\n"
                    + "4. 税务登记证复印件。",
                true, "test-model", "test", 20, 30));

        Map<String, Object> result = dialogService.send(
            "web", "unsupported-auth-materials", question, "咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("insufficient_evidence", result.get("answerStatus"));
        assertEquals("HANDOFF", result.get("answerDecision"));
        assertEquals("unsupported_enumerated_facts", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("信息不足"));
        assertFalse(((String) result.get("reply")).contains("组织机构代码证"));
        assertEquals(true, result.get("needsTransfer"));
        verify(handoffCoordinator).handoff(10L, "回答包含无依据的事实清单", "P1");
    }

    @Test
    void replacesUnsupportedOperationalStepsWithVerifiedScopeAndClarification() {
        String question = "合同签署怎么操作？";
        String verifiedAnswer = "签署前需要核验身份，签署完成后合同会存档。";
        String context = "问题：合同签署怎么操作？\n答案：" + verifiedAnswer
            + "\n事实：当前知识只确认身份核验和签署后存档。";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, context, 0.82, "rag", true,
                List.of(citation("chunk:signing", "document", 4L, "合同签署")),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(new ChatResponse(
                "操作步骤：\n1. 下载并安装点签 APP。\n2. 注册并登录账号。\n"
                    + "3. 完成身份核验。\n4. 选择合同模板。\n5. 点击签署按钮。",
                true, "test-model", "test", 20, 28));

        Map<String, Object> result = dialogService.send(
            "web", "unsupported-signing-steps", question, "咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("clarify", result.get("answerStatus"));
        assertEquals("CLARIFY", result.get("answerDecision"));
        assertEquals("unsupported_procedural_steps", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).startsWith(verifiedAnswer));
        assertTrue(((String) result.get("reply")).contains("补充要完成的具体操作"));
        assertFalse(((String) result.get("reply")).contains("点签 APP"));
        assertEquals(false, result.get("needsTransfer"));
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void repairsAContradictoryPositiveOpeningWithoutLosingTheAlternativeSteps() {
        String question = "合同已经发出但没人签，附件还能补进去吗？";
        String context = "合同发出后不能直接追加附件，需要先撤回原合同，"
            + "补齐附件后重新发起。";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, context, 0.84, "rag", true,
                List.of(citation("chunk:attachment", "document", 3L, "附件漏传")),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(new ChatResponse(
                "合同已经发出但没人签，附件还可以补进去。操作步骤如下：\n\n"
                    + "1. 先撤回原合同。\n2. 补齐附件后重新发起。",
                true, "test-model", "test", 20, 24));

        Map<String, Object> result = dialogService.send(
            "web", "negative-boundary-repair", question, "咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals("negative_boundary_consistency_guardrail",
            result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).startsWith("不能直接这样操作。"));
        assertTrue(((String) result.get("reply")).contains("先撤回原合同"));
        assertFalse(((String) result.get("reply")).contains("还可以补进去"));
        assertEquals(false, result.get("needsTransfer"));
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void returnsStructuredQaDirectAnswerWithoutCallingChatModel() {
        String question = "如何申请开票？";
        String answer = "第一步提交开票资料。\n第二步确认抬头。\n第三步下载电子发票。";
        Map<String, Object> source = citation("chunk:71", "document", 71L, "开票说明");
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, answer, "ctx", 1.0,
                "structured_qa_direct", true, List.of(source), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "user-structured-qa", question, "咨询");

        assertEquals("knowledge_qa", result.get("source"));
        assertEquals(answer, result.get("reply"));
        assertEquals(true, result.get("ragSource"));
        assertEquals("ANSWER", result.get("answerDecision"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void doesNotHandoffOnNoAnswerWhenAutomaticTransferIsDisabled() {
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        when(retrievalService.retrieve("火星办公室几点开门"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.18, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "dingtalk", "user-1", "火星办公室几点开门", "咨询");

        assertEquals(false, result.get("needsTransfer"));
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void routesConfiguredOutOfScopeQuestionBeforeKnowledgeRetrieval() {
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);

        for (String question : List.of(
                "实体锁怎么续期", "安徽CA锁延期怎么延期")) {
            Map<String, Object> result = dialogService.send(
                "wecom", "parent-company-" + question.hashCode(), question, "咨询");

            assertEquals(PARENT_COMPANY_REPLY, result.get("reply"));
            assertEquals("out_of_scope", result.get("source"));
            assertEquals("out_of_scope", result.get("answerStatus"));
            assertEquals("parent_company_out_of_scope", result.get("fallbackDecision"));
            assertEquals("NO_KNOWLEDGE", result.get("answerDecision"));
            assertEquals(false, result.get("lowConfidence"));
            assertEquals(false, result.get("needsTransfer"));
            assertEquals(Collections.emptyList(), result.get("citations"));
        }

        verify(retrievalService, never()).retrieve(anyString());
        verify(unmatchedQuestionService, never()).record(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void routesClearlyUnrelatedTimeAndWeatherQuestionsBeforeKnowledgeRetrieval() {
        for (String question : List.of(
                "现在是多少点？", "海口明天会下雨吗？", "今天哪只股票会涨？")) {
            Map<String, Object> result = dialogService.send(
                "web", "unrelated-" + question.hashCode(), question, "咨询");

            assertEquals(SCOPE_FALLBACK_REPLY, result.get("reply"));
            assertEquals("out_of_scope", result.get("source"));
            assertEquals("out_of_scope", result.get("answerStatus"));
            assertEquals("out_of_scope", result.get("fallbackDecision"));
            assertEquals("NO_KNOWLEDGE", result.get("answerDecision"));
            assertEquals(0.0, result.get("confidence"));
            assertEquals(Collections.emptyList(), result.get("citations"));
        }

        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersBasicConversationWithoutKnowledgeRetrievalOrChatModel() {
        Map<String, String> expectedReplies = new LinkedHashMap<>();
        expectedReplies.put("你是谁？", "我是点签电子合同官方智能客服，可以为您解答点签产品和使用相关问题。");
        expectedReplies.put("你能帮我做什么？",
            "我可以为您解答点签电子合同的平台功能、不同版本功能、使用操作、合同发起与签署、法律合规性、"
                + "企业认证、电子印章和合同管理等问题。涉及价格优惠、个案法律判断或需要核验身份与账户数据的操作，"
                + "我会交由人工客服进一步确认。");
        expectedReplies.put("您好！",
            "您好！我是点签智能客服，很高兴为您服务！请问您想咨询产品功能、合同签署流程、法律合规性相关问题？");
        expectedReplies.put("谢谢你", "不客气，很高兴能帮到您。");
        expectedReplies.put("再见", "好的，再见。需要咨询点签电子合同时，欢迎随时联系。");

        for (Map.Entry<String, String> entry : expectedReplies.entrySet()) {
            Map<String, Object> result = dialogService.send(
                "web", "basic-" + entry.getKey().hashCode(), entry.getKey(), "咨询");

            assertEquals(entry.getValue(), result.get("reply"));
            assertEquals("basic_conversation", result.get("source"));
            assertEquals("answered", result.get("answerStatus"));
            assertEquals("basic", result.get("answerMode"));
            assertEquals("ANSWER", result.get("answerDecision"));
            assertEquals(1.0, result.get("confidence"));
            assertEquals(false, result.get("ragSource"));
            assertEquals(0, result.get("ragContextChars"));
            assertEquals(false, result.get("needsTransfer"));
        }

        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(unmatchedQuestionService, never()).record(anyString());
    }

    @Test
    void recognizesNaturalIdentityAndCapabilityVariantsWithoutKnowledgeRetrieval() {
        Map<String, Object> identity = dialogService.send(
            "web", "basic-identity-particle", "请问你是谁呀？", "咨询");
        Map<String, Object> capability = dialogService.send(
            "web", "basic-capability-natural", "你都能帮客户处理哪些事？", "咨询");

        assertEquals("basic_conversation", identity.get("source"));
        assertTrue(((String) identity.get("reply")).contains("点签电子合同官方智能客服"));
        assertEquals("basic_conversation", capability.get("source"));
        assertTrue(((String) capability.get("reply")).contains("平台功能"));
        assertTrue(((String) capability.get("reply")).contains("不同版本功能"));
        assertTrue(((String) capability.get("reply")).contains("合同发起与签署"));
        assertTrue(((String) capability.get("reply")).contains("企业认证"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void treatsGreetingWithNaturalSuffixAsGreeting() {
        Map<String, Object> result = dialogService.send(
            "web", "greeting-suffix", "你好我是件", "咨询");

        assertEquals("basic_conversation", result.get("source"));
        assertEquals("basic_greeting", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("我是点签智能客服"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void resolvesBareWebpageLinkRequestToOfficialSite() {
        Map<String, Object> result = dialogService.send(
            "web", "bare-web-link", "给我一个网页链接", "咨询");

        assertEquals("basic_conversation", result.get("source"));
        assertEquals("basic_official_website", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("https://www.fs-signature.com/"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void clarifiesUnknownLowConfidenceQuestionInsteadOfMarkingItOutOfScope() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        when(retrievalService.retrieve("我姓王")).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.0, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "unknown-low-confidence", "我姓王", "咨询");

        assertEquals("clarify", result.get("source"));
        assertEquals("clarify", result.get("answerStatus"));
        assertEquals("CLARIFY", result.get("answerDecision"));
        assertEquals("clarify", result.get("fallbackDecision"));
        assertTrue(((Map<?, ?>) result.get("pendingClarification")).containsKey("attempt"));
        assertEquals(false, result.get("needsTransfer"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersCompoundRenewalQuestionFromMergedEvidenceWithoutModelGuessing() {
        String question = "明年不买套餐了，账号和以前签完的合同还能看吗？还能继续发新合同吗？";
        String accountContext = "套餐到期不续费后，点签账号依旧可以正常登录使用。";
        String contractContext = "之前的数据和签署完成的合同会一直保存，可以持续查阅、下载。";
        String launchContext = "不续费后不能进行合同的发起，如需签新的合同需要续费购买。";
        RagRetrievalService.RetrievalResult account = retrieval(accountContext, 0.93);
        RagRetrievalService.RetrievalResult contract = retrieval(contractContext, 0.92);
        RagRetrievalService.RetrievalResult launch = retrieval(launchContext, 0.91);
        RagRetrievalService.RetrievalResult accountAndContract = retrieval(
            accountContext + "\n" + contractContext, 0.93);
        RagRetrievalService.RetrievalResult allEvidence = retrieval(
            accountContext + "\n" + contractContext + "\n" + launchContext, 0.93);

        when(retrievalService.retrieve(
                "点签套餐到期不续费后，账号还能正常登录吗？",
                KNOWLEDGE_RETRIEVAL_FILTERS, true)).thenReturn(account);
        when(retrievalService.retrieve(
                "点签套餐到期不续费后，历史合同还能查阅和下载吗？",
                KNOWLEDGE_RETRIEVAL_FILTERS, true)).thenReturn(contract);
        when(retrievalService.retrieve(
                "点签套餐到期不续费后，还能继续发起新合同吗？",
                KNOWLEDGE_RETRIEVAL_FILTERS, true)).thenReturn(launch);
        when(retrievalService.mergeWithProvidedContext(
                account, contractContext, contract.citations())).thenReturn(accountAndContract);
        when(retrievalService.mergeWithProvidedContext(
                accountAndContract, launchContext, launch.citations())).thenReturn(allEvidence);

        Map<String, Object> result = dialogService.send(
            "web", "compound-renewal", question, "咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("evidence_consistency_guardrail", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("账号仍可正常登录"));
        assertTrue(((String) result.get("reply")).contains("查阅、下载"));
        assertTrue(((String) result.get("reply")).contains("不能进行合同的发起"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void keepsAutomaticAndManualSigningReminderFactsConsistent() {
        String question = "对方老忘签，系统能自动催吗？我自己还能把链接或者码发过去不？";
        String context = "合同发起后有签约提醒。自动方式包括短信、微信、钉钉消息提醒；"
            + "手动方式包括发送签约链接和二维码。";
        when(retrievalService.retrieve(question)).thenReturn(retrieval(context, 0.88));

        Map<String, Object> result = dialogService.send(
            "web", "signing-reminder-consistency", question, "咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("evidence_consistency_guardrail", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("短信、微信和钉钉"));
        assertTrue(((String) result.get("reply")).contains("链接或二维码"));
        assertFalse(((String) result.get("reply")).contains("不会自动"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void distinguishesKnowledgeGapFromUnrelatedQuestion() {
        ReflectionTestUtils.setField(dialogService, "noAnswerReply", "这个问题我暂时无法准确确认");
        ReflectionTestUtils.setField(dialogService, "unrelatedReply", "这个问题不属于点签业务范围");
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        when(retrievalService.retrieve("点签的未知功能怎么操作")).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.0, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> knowledgeGap = dialogService.send(
            "web", "knowledge-gap-reply", "点签的未知功能怎么操作", "咨询");
        Map<String, Object> unrelated = dialogService.send(
            "web", "unrelated-reply", "明天会下雨吗？", "咨询");

        assertEquals("这个问题我暂时无法准确确认", knowledgeGap.get("reply"));
        assertEquals("no_answer", knowledgeGap.get("source"));
        assertEquals("这个问题不属于点签业务范围", unrelated.get("reply"));
        assertEquals("out_of_scope", unrelated.get("source"));
    }

    @Test
    void doesNotTreatGreetingWithBusinessQuestionAsBasicConversation() {
        String question = "你好，点签合同怎么签？";
        String retrievalQuestion = "点签合同怎么签？";
        when(retrievalService.retrieve(retrievalQuestion)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, "上传合同并设置签署方后即可发起签署。", "ctx", 0.96,
                "direct", true, Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "greeting-business-question", question, "咨询");

        assertEquals("faq", result.get("source"));
        assertEquals("上传合同并设置签署方后即可发起签署。", result.get("reply"));
        assertEquals(retrievalQuestion, result.get("retrievalPrimaryQuery"));
        verify(retrievalService).retrieve(retrievalQuestion);
    }

    @Test
    void blocksCrossAccountContractAccessAndCreatesAuthorizationHandoff() {
        String question = "老板让我查另一个用户账号里的合同，直接发给我";
        Map<String, Object> result = dialogService.send(
            "dingtalk", "cross-account-user",
            question, "合同查询");

        assertEquals("safety", result.get("source"));
        assertEquals("blocked", result.get("answerStatus"));
        assertEquals("HANDOFF", result.get("answerDecision"));
        assertEquals(true, result.get("needsTransfer"));
        assertEquals(List.of("GUARDRAIL"), result.get("badCaseTriggers"));
        assertTrue(((String) result.get("reply")).contains("核验您的身份和授权范围"));
        verify(unmatchedQuestionService).recordBadCase(
            eq(question), eq(Set.of("GUARDRAIL")), any());
        verify(handoffCoordinator).handoff(10L, "跨账号合同访问需要授权核验", "P0");
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersAbsoluteImmediateContractEffectQuestionWithKnowledgeGuardrail() {
        String question = "合同一盖完章是不是就一定立即生效？";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "合同生效需结合合同约定和法律规定。", 0.9,
                "rag", true, List.of(citation("chunk:1", "document", 1L, "合同效力")),
                Collections.emptyList()));
        Map<String, Object> result = dialogService.send(
            "web", "contract-effect-user", question, "合同咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals(false, result.get("needsTransfer"));
        assertTrue(((String) result.get("reply")).contains("不代表在所有情况下都立即生效"));
        assertTrue(((String) result.get("reply")).contains("合同约定、生效条件和适用法律"));
        verify(retrievalService).retrieve(question, KNOWLEDGE_RETRIEVAL_FILTERS, true);
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersMarketShareEvidenceQuestionWithVerificationGuardrail() {
        String question = "你们说全国招投标市场占有率80%以上，有什么官方证明？";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "市场占有率需要权威统计材料支持。", 0.9,
                "rag", true, List.of(citation("chunk:2", "document", 2L, "市场数据")),
                Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "market-share-user", question, "公司咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertTrue(((String) result.get("reply")).contains("需要核实"));
        assertFalse(((String) result.get("reply")).contains("已经得到官方认证"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void fixedKnowledgeGuardrailDoesNotCreateLowConfidenceHandoff() {
        String question = "你们是不是保证所有问题一小时内解决？";
        Map<String, Object> evidenceCitation =
            citation("chunk:91", "document", 91L, "海南本地化服务优势");
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null,
                "飞晟科技针对本地企业提供远程问题1小时内响应，并可在预约后12小时内上门协助。",
                0.531, "rag", true, List.of(evidenceCitation), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "response-promise-guardrail", question, "服务咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("knowledge_guardrail", result.get("fallbackDecision"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals(true, result.get("lowConfidence"));
        assertEquals(false, result.get("needsTransfer"));
        assertEquals(List.of("GUARDRAIL", "LOW_CONFIDENCE"),
            result.get("badCaseTriggers"));
        assertEquals(List.of(evidenceCitation), result.get("citations"));
        assertTrue(((String) result.get("reply")).contains("1小时内响应"));
        assertTrue(((String) result.get("reply")).contains("不等于问题已解决"));
        verify(unmatchedQuestionService).recordBadCase(
            eq(question), eq(Set.of("GUARDRAIL", "LOW_CONFIDENCE")), any());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void doesNotBlockBusinessQuestionThatMentionsCurrentTime() {
        String question = "客服现在上班吗？";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, "客服工作时间为周一至周五 9:00-18:00。", "客服工作时间", 1.0,
                "direct", true, Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "business-time", question, "咨询");

        assertEquals("客服工作时间为周一至周五 9:00-18:00。", result.get("reply"));
        assertEquals("faq", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        verify(retrievalService).retrieve(question, KNOWLEDGE_RETRIEVAL_FILTERS, true);
    }

    @Test
    void clarifiesUnknownDianqianPageBeforeKnowledgeRetrieval() {
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);

        Map<String, Object> result = dialogService.send(
            "wecom", "user-dianqian", "点签的这个页面怎么操作", "咨询");

        assertEquals("请补充具体场景", result.get("reply"));
        assertEquals("clarify", result.get("source"));
        assertEquals("clarify", result.get("answerStatus"));
        assertEquals("unresolved_reference", result.get("fallbackDecision"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(unmatchedQuestionService, never()).record(anyString());
    }

    @Test
    void answersKnownPartForSpecificContractCapabilityWithoutOverclaiming() {
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        String retrievalQuery = "点签 是否支持签署 量子合同";
        when(retrievalService.retrieve(retrievalQuery)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "点签支持电子合同在线签署。", 0.68, "rag", true,
                List.of(citation("chunk:18", "document", 18L, "点签介绍")),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), any()))
            .thenReturn(new ChatResponse(
                "__ANSWER_PARTIAL__\n点签可以发起和签署电子合同，但现有信息不能确认量子合同。请问具体签署主体和使用场景是什么？",
                true, "test", "test", 30, 20));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "wecom", "user-dianqian-sentinel", "点签支持量子合同吗", "咨询");

        assertTrue(((String) result.get("reply")).contains("不能确认量子合同"));
        assertEquals("rag_ai", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("ANSWER_PARTIAL", result.get("answerDecision"));
        assertEquals("partial_rag",
            ((Map<?, ?>) result.get("retrieval")).get("decision"));
        assertEquals(false, result.get("needsTransfer"));
        assertEquals(1, ((List<?>) result.get("citations")).size());
        assertEquals(retrievalQuery, result.get("retrievalQuery"));
        assertEquals("CONTRACT_TYPE_CAPABILITY",
            ((Map<?, ?>) result.get("nlpIntent")).get("intentCode"));
        verify(unmatchedQuestionService, never()).record(anyString());
    }

    @Test
    void clarifiesLoanContractDraftingInsteadOfUsingGenericHighRiskReply() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        String question = "我要签借款合同，这个怎么写？";
        String retrievalQuery = "借款合同 内容怎么写 起草模板";
        when(retrievalService.retrieve(retrievalQuery)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.0, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "loan-contract-drafting", question, "合同咨询");

        assertEquals("clarify", result.get("source"));
        assertEquals("clarify", result.get("answerStatus"));
        assertEquals("contract_drafting_clarify", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("借款合同内容怎么写"));
        assertTrue(((String) result.get("reply")).contains("合同内容”或“发起签署"));
        assertEquals(false, result.get("needsTransfer"));
        assertEquals("CONTRACT_DRAFTING",
            ((Map<?, ?>) result.get("nlpIntent")).get("intentCode"));
        assertEquals("draftingGoal",
            ((Map<?, ?>) result.get("pendingClarification")).get("missingSlot"));
        assertEquals("CLARIFY",
            ((Map<?, ?>) result.get("serviceDecision")).get("decision"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void doesNotConfirmPropertySaleContractCapabilityWithoutProductEvidence() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        String question = "你们平台可以签房屋买卖合同吗？";
        String retrievalQuery = "点签 是否支持签署 房屋买卖合同";
        when(retrievalService.retrieve(retrievalQuery)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.0, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "property-contract-capability", question, "合同咨询");

        assertEquals("no_answer", result.get("source"));
        assertEquals("no_answer", result.get("answerStatus"));
        assertEquals("NO_KNOWLEDGE", result.get("answerDecision"));
        assertEquals("contract_capability_no_evidence", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("不能据此判断是否可用"));
        assertFalse(((String) result.get("reply")).contains("支持房屋买卖合同"));
        assertEquals(true, result.get("needsTransfer"));
        assertEquals(true,
            ((Map<?, ?>) result.get("nlpIntent")).get("generallySupportedContractType"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void handsOffLegalRiskWithAContractSpecificReplyWhenEvidenceIsMissing() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        String question = "电子合同有法律效力吗？";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.0, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "contract-legal-risk", question, "合同咨询");

        assertEquals("contract_legal_risk", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("合同法律效力或条款判断"));
        assertEquals(true, result.get("needsTransfer"));
        assertEquals("CONTRACT_LEGAL_RISK",
            ((Map<?, ?>) result.get("nlpIntent")).get("intentCode"));
        verify(handoffCoordinator).handoff(
            10L, "高风险业务问题缺少可核实依据", "P1");
    }

    @Test
    void handsOffHighRiskQuestionWhenNativeFallbackIsDisabled() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", false);
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        String question = "电子合同有法律效力吗？";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.0, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "contract-legal-risk-disabled-fallback", question, "合同咨询");

        assertEquals("answerability_gate_high_risk_no_evidence",
            result.get("fallbackDecision"));
        assertEquals("HANDOFF", result.get("answerDecision"));
        assertEquals(true, result.get("needsTransfer"));
        assertEquals("HANDOFF",
            ((Map<?, ?>) result.get("answerabilityGate")).get("decision"));
        assertEquals("high_risk_no_evidence",
            ((Map<?, ?>) result.get("answerabilityGate")).get("reasonCode"));
        verify(handoffCoordinator).handoff(
            10L, "高风险业务问题缺少可核实依据", "P1");
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void broadProductQuestionDoesNotTriggerTheHighRiskFixedReply() {
        String question = "你们平台有哪些功能？";

        Map<String, Object> result = dialogService.send(
            "web", "product-features", question, "产品咨询");

        assertEquals("basic_product_features", result.get("fallbackDecision"));
        assertEquals("basic_conversation", result.get("source"));
        assertEquals("product_features", result.get("basicIntent"));
        assertTrue(((String) result.get("reply")).contains("点签电子合同主要包含的7大功能"));
        assertEquals(Collections.emptyList(), result.get("attachments"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersCompanyIntroductionQuestionWhenMatchingEvidenceIsJustBelowHandoffThreshold() {
        String question = "你们公司怎么样？";
        String normalizedQuestion = "你们主要是做什么的？";
        Map<String, Object> companyCitation = citation(
            "faq:28", "faq", 28L, "你们主要是做什么的？");
        when(retrievalService.retrieve(normalizedQuestion)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, "飞晟科技是一家软件开发企业。",
                "【企业内部事实】\n事实：你们主要是做什么的？\n"
                    + "答案：飞晟科技是一家软件开发企业，主要提供电子签约相关产品和服务。",
                1.0, "direct", false, List.of(companyCitation), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(
                new ChatResponse("__NO_ANSWER__", true, "test-model", "test", 20, 3),
                new ChatResponse("飞晟科技是一家软件开发企业，主要提供电子签约相关产品和服务。",
                    true, "test-model", "test", 24, 18));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "dingtalk", "company-introduction-user", question, "咨询");

        assertEquals("rag_ai", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("company_intro_retry", result.get("fallbackDecision"));
        assertEquals(false, result.get("lowConfidence"));
        assertEquals(false, result.get("needsTransfer"));
        assertEquals(List.of(companyCitation), result.get("citations"));
        assertTrue(((String) result.get("reply")).contains("软件开发企业"));
        assertEquals(2, result.get("modelInvocationCount"));
        assertEquals(44, result.get("modelInputTokens"));
        assertEquals(21, result.get("modelOutputTokens"));
        assertEquals(2, ((List<?>) result.get("promptInvocations")).size());
        assertEquals(1, ((Map<?, ?>) ((List<?>) result.get("promptInvocations")).get(0))
            .get("attempt"));
        assertEquals(2, ((Map<?, ?>) ((List<?>) result.get("promptInvocations")).get(1))
            .get("attempt"));
        assertEquals(normalizedQuestion, result.get("retrievalQuery"));
        assertEquals(true, result.get("queryRewritten"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelService, times(2)).chatWithModel(
            promptCaptor.capture(), systemPromptCaptor.capture(), isNull());
        assertTrue(systemPromptCaptor.getAllValues().get(0).contains("请求公司介绍"));
        assertTrue(promptCaptor.getAllValues().get(1).contains("再次确认"));
        verify(retrievalService).retrieve(normalizedQuestion);
        verify(unmatchedQuestionService, never()).record(anyString());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void usesAnswerableEvidenceBelowTheHandoffConfidenceThreshold() {
        String question = "你们公司靠谱吗？";
        Map<String, Object> companyCitation = citation(
            "faq:28", "faq", 28L, "你们主要是做什么的？");
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "飞晟科技主要提供电子签约相关产品和服务。", 0.50,
                "rag", true,
                List.of(companyCitation), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")), isNull()))
            .thenReturn(new ChatResponse(
                "飞晟科技主要提供电子签约相关产品和服务。", true,
                "test-model", "test", 20, 12));

        Map<String, Object> result = dialogService.send(
            "dingtalk", "company-reputation-user", question, "咨询");

        assertEquals("rag_ai", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals(List.of(companyCitation), result.get("citations"));
        assertEquals(true, result.get("lowConfidence"));
        assertEquals(true, result.get("needsTransfer"));
        assertEquals(88L, ((Map<?, ?>) result.get("handoff")).get("ticketId"));
        verify(aiModelService).chatWithModel(anyString(), argThat(prompt ->
            prompt.startsWith("rag-system-prompt")), isNull());
        verify(handoffCoordinator).handoff(
            10L, "AI 回答置信度低于阈值", "P1");
        verify(unmatchedQuestionService, never()).record(anyString());
    }

    @Test
    void keepsPriceOutOfRagEvenWhenKnowledgeWouldOtherwiseMatch() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);

        Map<String, Object> result = dialogService.send(
            "wecom", "user-high-risk-sentinel", "量子合同定制报价是多少", "咨询");

        assertEquals("price_qualification", result.get("source"));
        assertEquals("unified_contract_pricing", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("https://www.fs-signature.com/"));
        assertEquals(false, result.get("needsTransfer"));
        assertEquals(Collections.emptyList(), result.get("citations"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(
            anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void answersDianqianProductFeaturesWithoutFallingBackToVersionFeaturesOrModel() {
        for (String question : List.of("产品功能", "点签的产品功能", "点签有哪些功能？")) {
            Map<String, Object> result = dialogService.send(
                "web", "product-features-" + question.hashCode(), question, "咨询");

            assertEquals("basic_conversation", result.get("source"));
            assertEquals("product_features", result.get("basicIntent"));
            assertTrue(((String) result.get("reply")).contains("点签电子合同主要包含的7大功能"));
            assertTrue(((String) result.get("reply")).contains("企业印章"));
            assertTrue(((String) result.get("reply")).contains("合同状态提醒"));
            assertEquals(Collections.emptyList(), result.get("attachments"));
        }

        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersSigningFlowAndLegalComplianceExactlyBeforeKnowledgeRetrieval() {
        Map<String, String> expectedReplies = Map.of(
            "合同签署流程", """
                您好！关于电子合同的签署流程，我为您详细说明一下：

                签署流程如下：

                1. 发起合同：企业发起人（如销售、HR等）在点签平台填写合同信息，并指定接收方（如客户、员工等）的手机号码，然后发起合同。
                2. 接收方签署：合同发起后，接收方会收到一条签署短信，也可以通过微信公众号、小程序或PC网页端进入签署页面。接收方需先完成实名认证（身份信息会与公安人口数据库、三大运营商数据比对，确保真实有效），然后即可查看并签署合同。
                3. 双方完成签署：双方（发起方和接收方）完成盖章或签名动作后，合同即完成签署，立即生效。
                4. 合同存档：签署完成的合同会各自存档在双方的点签账号中，随时可以调用查阅、下载，与纸质合同具有同等法律效力。

                简单来说：发起 → 接收方签署 → 发起方签署 → 生效存档。
                """.strip(),
            "法律合规性", """
                您好！关于电子合同的法律合规性，这是非常关键的问题，请您放心，我来为您详细解答：

                1. 电子合同具有法律效力
                根据国家2004年出台的《电子签名法》明确规定：可靠的电子签名与手写签名或盖章具有同等的法律效力。因此，在点签平台上签署的电子合同，与传统的纸质合同一样，具备同等的法律效力。

                2. 我们的签署流程严格遵循法律要求
                我们的签署流程都是按照国家出台的《电子签名法》的要求严格执行的。在平台上签署的每一份合同，都确保：

                - 实名认证：签署方需通过身份信息比对（与公安人口数据库、三大运营商数据比对），确保签署人真实身份。
                - 时间戳：每次签署都会加盖国家授时中心的时间戳，确保签署时间不可篡改。
                - 加密存储：合同数据经过加密处理，确保签署内容的安全性和完整性。

                3. 电子合同不适用的情况
                根据《电子签名法》第三条，以下情形不适合使用电子合同：

                - 涉及婚姻、收养、继承等人身关系的；
                - 涉及停止供水、供热、供气等公用事业服务的；
                - 法律、行政法规规定的不适用电子文书的其他情形。

                4. 我们的资质保障
                电子合同具有一定的特殊性，必须要经过相应的国家机构授权及资质证书的保障。我们点签平台是正规机构，拥有相关资质，为您提供安全、合规的电子合同服务。
                """.strip());

        for (Map.Entry<String, String> entry : expectedReplies.entrySet()) {
            Map<String, Object> result = dialogService.send(
                "web", "fixed-menu-" + entry.getKey().hashCode(), entry.getKey(), "咨询");

            assertEquals(entry.getValue(), result.get("reply"));
            assertEquals("basic_conversation", result.get("source"));
            assertEquals("answered", result.get("answerStatus"));
            assertEquals(false, result.get("needsTransfer"));
        }

        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    @Test
    void returnsVersionFeatureMatrixWithoutKnowledgeRetrieval() {
        KnowledgeImageService.ImageAttachment attachment =
            new KnowledgeImageService.ImageAttachment(
                "image", 66L, "点签产品版本功能.png", "/api/image/66");
        when(replyAttachmentService.fromKnowledgeImageTitle("点签产品版本功能.png"))
            .thenReturn(List.of(attachment));

        Map<String, Object> result = dialogService.send(
            "web", "version-features", "版本功能", "咨询");

        assertEquals("点签产品版本功能如下：", result.get("reply"));
        assertEquals("version_features", result.get("basicIntent"));
        assertEquals(List.of(attachment), result.get("attachments"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void keepsConfiguredNoAnswerReplyUnchangedForAnxiousUser() {
        when(retrievalService.retrieve("我很担心，火星办公室几点开门"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.18, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "user-anxious", "我很担心，火星办公室几点开门", "咨询");

        assertEquals(SCOPE_FALLBACK_REPLY, result.get("reply"));
        assertEquals("ANXIETY", ((Map<?, ?>) result.get("emotion")).get("label"));
    }

    @Test
    void adaptsFaqReplyForAnxiousUser() {
        when(retrievalService.retrieve("我很担心，订单什么时候送到"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                true, true, "订单预计明天送达。", "ctx", 0.92, "direct", true,
                Collections.emptyList(), Collections.emptyList()));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-anxious-faq", "我很担心，订单什么时候送到", "订单咨询");

        assertEquals("请放心，我来帮您确认。订单预计明天送达。", result.get("reply"));
        assertEquals("ANXIETY", ((Map<?, ?>) result.get("emotion")).get("label"));
        verify(conversationService).updateStatus(any(BotConversation.class));
    }

    @Test
    void escalatesStrongAngerAsP0() {
        String question = "真的气死我了！太垃圾了！我要投诉";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, "我们会立即核实。", "ctx", 0.92, "direct", true,
                Collections.emptyList(), Collections.emptyList()));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-angry", question, "投诉");

        assertEquals(true, result.get("needsTransfer"));
        assertEquals("HIGH", ((Map<?, ?>) result.get("emotion")).get("risk"));
        verify(handoffCoordinator).handoff(eq(10L),
            org.mockito.ArgumentMatchers.contains("用户情绪高风险"), eq("P0"));
    }

    @Test
    void clearsCitationsWhenModelFailsAfterRetrieval() {
        Map<String, Object> citation = citation("chunk:9", "document", 9L, "员工手册");
        when(retrievalService.retrieve("年假有几天")).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "知识库上下文", 0.74, "rag", true,
                List.of(citation), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")), eq(null)))
            .thenReturn(new ChatResponse("AI服务暂时不可用", false, "fallback", "fallback", 0, 0));

        Map<String, Object> result = dialogService.send(
            "web", "user-2", "年假有几天", "咨询");

        assertEquals("error", result.get("source"));
        assertEquals("error", result.get("answerStatus"));
        assertEquals(false, result.get("ragSource"));
        assertEquals(Collections.emptyList(), result.get("citations"));
        assertEquals("INTERNAL_ERROR", result.get("errorCode"));
        assertEquals(true, result.get("needsTransfer"));
    }

    @Test
    void returnsSingleStructuredAnswerWhenModelFailsAfterRetrieval() {
        Map<String, Object> citation = citation(
            "chunk:9", "document", 9L, "企业怎么认证？");
        String context = """
            【企业内部事实】
            事实：企业怎么认证？
            问题：企业怎么认证？
            答案：企业认证支持法人认证、法人授权和对公打款三种方式。
            回答时先锁定客户明确询问的产品、业务或对象。
            """;
        when(retrievalService.retrieve("企业怎么认证？")).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, context, 0.74, "rag", true,
                List.of(citation), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")), eq(null)))
            .thenReturn(new ChatResponse(
                "AI服务暂时不可用", false, "fallback", "fallback", 0, 0));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-structured-fallback", "企业怎么认证？", "咨询");

        assertEquals("knowledge_qa", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("model_failure_knowledge", result.get("fallbackDecision"));
        assertEquals("企业认证支持法人认证、法人授权和对公打款三种方式。", result.get("reply"));
        assertEquals(List.of(citation), result.get("citations"));
        assertEquals(true, result.get("ragSource"));
        assertEquals(false, result.get("needsTransfer"));
    }

    @Test
    void doesNotChooseBetweenConflictingStructuredAnswersWhenModelFails() {
        Map<String, Object> citation = citation(
            "chunk:9", "document", 9L, "认证说明");
        String context = """
            【企业内部事实】
            事实：认证方式一
            问题：企业怎么认证？
            答案：可以通过法人认证。
            事实：认证方式二
            问题：企业怎么认证？
            答案：必须通过对公打款认证。
            回答时先锁定客户明确询问的产品、业务或对象。
            """;
        when(retrievalService.retrieve("企业怎么认证？")).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, context, 0.74, "rag", true,
                List.of(citation), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")), eq(null)))
            .thenReturn(new ChatResponse(
                "AI服务暂时不可用", false, "fallback", "fallback", 0, 0));

        Map<String, Object> result = dialogService.send(
            "web", "user-conflicting-fallback", "企业怎么认证？", "咨询");

        assertEquals("error", result.get("source"));
        assertEquals(Collections.emptyList(), result.get("citations"));
        assertEquals(true, result.get("needsTransfer"));
    }

    @Test
    void combinesAttachedImageEvidenceWithGlobalRetrieval() {
        String ocrText = "订单状态：支付失败";
        String imageContext = "【截图依据】\n[1] " + ocrText;
        Map<String, Object> imageCitation = citation("image:12", "image", 12L, "订单截图");
        Map<String, Object> knowledgeCitation = citation(
            "chunk:9", "document", 9L, "支付故障手册");
        RagRetrievalService.RetrievalResult knowledge = new RagRetrievalService.RetrievalResult(
            true, true, "直接答案不应覆盖截图", "【知识库依据】\n[1] 支付故障处理",
            0.78, "direct", true, List.of(knowledgeCitation), Collections.emptyList());
        RagRetrievalService.RetrievalResult merged = new RagRetrievalService.RetrievalResult(
            true, false, null, imageContext + "\n\n【知识库依据】\n[2] 支付故障处理",
            0.78, "multimodal_rag", true, List.of(imageCitation, knowledgeCitation),
            Collections.emptyList());
        when(retrievalService.retrieve("这个怎么处理", ocrText, true)).thenReturn(knowledge);
        when(retrievalService.mergeWithProvidedContext(
                knowledge, imageContext, List.of(imageCitation)))
            .thenReturn(merged);
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")), eq(null)))
            .thenReturn(new ChatResponse("请重试支付 [1][2]", true,
                "test-model", "test", 30, 10));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.sendWithMultimodalContext(
            "playground", "admin-preview", "这个怎么处理", "截图问答",
            imageContext, List.of(imageCitation), ocrText, null);

        assertEquals("rag_ai", result.get("source"));
        assertEquals("multimodal_rag",
            ((Map<?, ?>) result.get("retrieval")).get("decision"));
        assertEquals(List.of(imageCitation, knowledgeCitation), result.get("citations"));
        assertEquals("请重试支付", result.get("reply"));
        verify(retrievalService).retrieve(
            "这个怎么处理", null, ocrText, KNOWLEDGE_RETRIEVAL_FILTERS, true);
        verify(aiModelService).chatWithModel(
            org.mockito.ArgumentMatchers.contains("[2] 支付故障处理"),
            argThat(prompt -> prompt.startsWith("rag-system-prompt")), eq(null));
    }

    @Test
    void scopesMultimodalGlobalRetrievalWithConversationHistory() {
        String question = "它到期后还能用吗？";
        String standaloneQuery = "我想了解专业版电子合同套餐 " + question;
        String ocrText = "套餐页面显示即将到期";
        String imageContext = "【截图依据】\n[1] " + ocrText;
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "我想了解专业版电子合同套餐"),
            message("ai", "专业版支持合同管理和到期提醒。"),
            message("user", question)));

        dialogService.sendWithMultimodalContext(
            "playground", "history-image-user", question, "套餐截图",
            imageContext, Collections.emptyList(), ocrText, null);

        verify(retrievalService).retrieve(
            eq(standaloneQuery), isNull(), eq(ocrText),
            eq(KNOWLEDGE_RETRIEVAL_FILTERS),
            argThat((List<QueryVariant> variants) -> variants.size() == 1
                && question.equals(variants.get(0).query())
                && "context_follow_up".equals(variants.get(0).purpose())),
            eq(true));
    }

    @Test
    void usesRecentConversationForSubjectlessFollowUpRetrieval() {
        String question = "它到期后还能用吗？";
        String standaloneQuery = "我想了解专业版电子合同套餐 " + question;
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "我想了解专业版电子合同套餐"),
            message("ai", "专业版支持合同管理和到期提醒。"),
            message("user", question)));
        when(retrievalService.retrieve(standaloneQuery))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.3, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "user-3", question, "咨询");

        verify(retrievalService).retrieve(standaloneQuery);
        assertEquals(standaloneQuery, result.get("retrievalQuery"));
        assertEquals(true, result.get("queryRewritten"));
        assertEquals(true, result.get("retrievalContextUsed"));
        assertEquals(false, result.get("retrievalHistoryUsed"));
    }

    @Test
    void retrievesAnaphoricContractFollowUpAsAStandaloneQuery() {
        String previousQuestion = "我刚发起一份合同，发现正文写错了，对方还没有签。";
        String question = "这个还能直接改吗？";
        String standaloneQuery = previousQuestion + " " + question;
        Map<String, Object> evidenceCitation =
            citation("chunk:24", "document", 62L, "合同发起后能否修改正文");
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", previousQuestion),
            message("user", question)));
        when(retrievalService.retrieve(standaloneQuery)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null,
                "对方未签署时，需撤回合同，修改正文后重新发起。", 0.82,
                "rag", true, List.of(evidenceCitation), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")), isNull()))
            .thenReturn(new ChatResponse(
                "不能直接修改。请先撤回合同，修改正文后重新发起。", true,
                "test-model", "test", 30, 14));

        Map<String, Object> result = dialogService.send(
            "web", "wrong-content-follow-up", question, "合同咨询");

        verify(retrievalService).retrieve(standaloneQuery);
        assertEquals(standaloneQuery, result.get("retrievalQuery"));
        assertEquals(true, result.get("queryRewritten"));
        assertEquals(false, result.get("retrievalHistoryUsed"));
        assertEquals("rag_ai", result.get("source"));
        assertEquals(List.of(evidenceCitation), result.get("citations"));
        assertTrue(((String) result.get("reply")).contains("撤回"));
        assertTrue(((String) result.get("reply")).contains("重新发起"));
    }

    @Test
    void retrievesProceduralFollowUpWithResolvedAndOriginalQueryVariants() {
        String previousQuestion = "我们公司的法人刚刚变更，点签里还是旧法人。";
        String question = "具体先做哪一步？";
        String standaloneQuery = previousQuestion + " " + question;
        Map<String, Object> evidenceCitation =
            citation("chunk:5708", "document", 5708L, "企业法人变更办理步骤");
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", previousQuestion),
            message("ai", "需要更新企业认证信息。"),
            message("user", question)));
        when(retrievalService.retrieve(
                eq(standaloneQuery), isNull(), isNull(),
                eq(KNOWLEDGE_RETRIEVAL_FILTERS), anyList(), eq(true)))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                true, false, null,
                "企业法人变更后，先进入企业信息页面提交变更申请。", 0.86,
                "rag", true, List.of(evidenceCitation), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")), isNull()))
            .thenReturn(new ChatResponse(
                "第一步先进入企业信息页面提交法人变更申请。", true,
                "test-model", "test", 32, 12));

        Map<String, Object> result = dialogService.send(
            "web", "legal-person-follow-up", question, "企业信息变更");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QueryVariant>> variantsCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrievalService).retrieve(
            eq(standaloneQuery), isNull(), isNull(),
            eq(KNOWLEDGE_RETRIEVAL_FILTERS), variantsCaptor.capture(), eq(true));
        assertEquals(1, variantsCaptor.getValue().size());
        assertEquals(question, variantsCaptor.getValue().get(0).query());
        assertEquals("context_follow_up", variantsCaptor.getValue().get(0).purpose());
        assertEquals(standaloneQuery, result.get("contextResolvedQuery"));
        assertEquals(true, result.get("contextResolutionApplied"));
        assertEquals(true, result.get("queryContextDependent"));
        assertEquals(List.of(standaloneQuery, question), result.get("retrievalVariants"));
        assertEquals(List.of(evidenceCitation), result.get("citations"));
    }

    @Test
    void resolvesLegacyContractTypeAnswerBeforeIntentClassification() {
        String question = "二手房买卖合同";
        String canonicalQuery = "点签 是否支持签署 二手房买卖合同";
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "您们平台可以签房屋买卖合同吗？"),
            message("ai", "点签平台支持电子合同签署。"
                + "请问您要签署的是商品房买卖合同还是二手房买卖合同？"),
            message("user", question)));
        when(retrievalService.retrieve(
                eq(canonicalQuery), isNull(), isNull(),
                eq(KNOWLEDGE_RETRIEVAL_FILTERS), anyList(), eq(true)))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                true, true, "可以使用点签签署二手房买卖合同。",
                "点签支持二手房买卖合同电子签署。", 0.91,
                "structured_qa_direct", true,
                Collections.emptyList(), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(new ChatResponse(
                "可以使用点签签署二手房买卖合同。", true,
                "test-model", "test", 30, 12));

        Map<String, Object> result = dialogService.send(
            "web", "property-contract-follow-up", question, "合同咨询");

        assertEquals(canonicalQuery, result.get("contextResolvedQuery"));
        assertEquals(canonicalQuery, result.get("retrievalQuery"));
        assertEquals(true, result.get("queryContextDependent"));
        assertEquals(true, result.get("contextResolutionApplied"));
        assertEquals(true, result.get("clarificationStateConsumed"));
        assertEquals("legacy", result.get("clarificationResolutionSource"));
        assertEquals("CONTRACT_TYPE_CAPABILITY",
            ((Map<?, ?>) result.get("nlpIntent")).get("intentCode"));
        assertEquals(List.of(canonicalQuery, question), result.get("retrievalVariants"));
        assertEquals("rag_ai", result.get("source"));
    }

    @Test
    void rewritesEnterpriseFollowUpBeforeRetrievalAndKeepsContractTopic() {
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "我要怎么签合同"),
            message("ai", "可以手写签名或使用电子签名。"),
            message("user", "企业的呢？")));
        when(retrievalService.retrieve("企业怎么签合同"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                true, false, null, "企业完成认证后可以发起和签署电子合同。",
                0.91, "rag", true, Collections.emptyList(), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")), eq(null)))
            .thenReturn(new ChatResponse("企业可以使用电子合同。", true,
                "test-model", "test", 30, 10));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "enterprise-follow-up", "企业的呢？", "咨询");

        verify(retrievalService).retrieve("企业怎么签合同");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(
            promptCaptor.capture(), argThat(prompt -> prompt.startsWith("rag-system-prompt")),
            eq(null));
        assertTrue(promptCaptor.getValue().contains("补全后的本轮意图：企业怎么签合同"));
        assertEquals("企业怎么签合同", result.get("retrievalQuery"));
        assertEquals(true, result.get("queryRewritten"));
        assertEquals(false, result.get("retrievalHistoryUsed"));
    }

    @Test
    void inheritsDianqianProductForEnterpriseLoginFollowUp() {
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "我在你们点签电子合同，购买的合同如何查询是否被篡改？"),
            message("ai", "可以登录点签官网验签页面上传文件进行验签。"),
            message("user", "企业怎么登录？")));
        when(retrievalService.retrieve("点签电子合同 企业怎么登录？"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                true, true, "企业可登录点签。",
                "用户先用手机号注册登录并完成个人认证，再创建并认证企业。",
                0.78, "rag", true, Collections.emptyList(), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), argThat(prompt ->
                prompt.startsWith("rag-system-prompt")), eq(null)))
            .thenReturn(new ChatResponse(
                "先用手机号登录点签并完成个人认证，再创建并认证企业。", true,
                "test-model", "test", 30, 10));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "dingtalk", "enterprise-login-follow-up", "企业怎么登录？", "咨询");

        verify(retrievalService).retrieve("点签电子合同 企业怎么登录？");
        assertEquals("点签电子合同 企业怎么登录？", result.get("retrievalQuery"));
        assertEquals(true, result.get("queryRewritten"));
        assertEquals(true, result.get("retrievalContextUsed"));
        assertEquals(false, result.get("retrievalHistoryUsed"));
        assertEquals("rag_ai", result.get("source"));
    }

    @Test
    void usesCanonicalFeatureQueryAfterProductFollowUpResolution() {
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "点签是什么？"),
            message("ai", "点签是一款电子合同应用。"),
            message("user", "有什么功能？")));

        Map<String, Object> result = dialogService.send(
            "playground", "feature-follow-up", "有什么功能？", "试聊");

        assertEquals("basic_conversation", result.get("source"));
        assertEquals("product_features", result.get("basicIntent"));
        assertTrue(((String) result.get("reply")).contains("点签电子合同主要包含的7大功能"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void doesNotPolluteStandaloneQuestionWithPreviousTopic() {
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "套餐是多少钱"),
            message("ai", "不同套餐价格不同。"),
            message("user", "电子合同有法律效力吗？")));
        when(retrievalService.retrieve("电子合同有法律效力吗？"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.3, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "user-4", "电子合同有法律效力吗？", "咨询");

        verify(retrievalService).retrieve("电子合同有法律效力吗？");
        assertEquals(false, result.get("retrievalContextUsed"));
    }

    @Test
    void redactsUserInputAndFinalAnswerAcrossTheDialogPipeline() {
        String safeQuestion = "我的手机号[手机号已脱敏]，怎么申请售后";
        when(retrievalService.retrieve(safeQuestion)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, "请发邮件到agent@example.com", "ctx", 0.92,
                "direct", true, Collections.emptyList(), Collections.emptyList()));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-pii", "我的手机号13800138000，怎么申请售后", "售后咨询");

        assertEquals("请发邮件到[邮箱已脱敏]", result.get("reply"));
        assertEquals(true, result.get("redactionApplied"));
        assertTrue(((List<?>) result.get("redactedTypes")).contains("PHONE"));
        assertTrue(((List<?>) result.get("redactedTypes")).contains("EMAIL"));
        verify(retrievalService).retrieve(safeQuestion);

        ArgumentCaptor<BotMessage> messageCaptor = ArgumentCaptor.forClass(BotMessage.class);
        verify(messageService, times(2)).save(messageCaptor.capture());
        assertEquals(safeQuestion, messageCaptor.getAllValues().get(0).getContent());
        assertEquals("请发邮件到[邮箱已脱敏]",
            messageCaptor.getAllValues().get(1).getContent());
    }

    @Test
    void createsHandoffForLowConfidenceAnswer() {
        String question = "点签支持电子骑缝章吗";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, "支持。", "ctx", 0.52, "direct", true,
                Collections.emptyList(), Collections.emptyList()));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-low-confidence", question, "咨询");

        assertEquals(true, result.get("needsTransfer"));
        assertEquals(true, result.get("lowConfidence"));
        assertEquals(List.of("LOW_CONFIDENCE"), result.get("badCaseTriggers"));
        assertEquals(88L, ((Map<?, ?>) result.get("handoff")).get("ticketId"));
        verify(unmatchedQuestionService).recordBadCase(
            eq(question), eq(Set.of("LOW_CONFIDENCE")), any());
        verify(handoffCoordinator).handoff(
            10L, "AI 回答置信度低于阈值", "P1");
    }

    @Test
    void blocksLowConfidenceRagBeforeCallingTheModel() {
        String question = "企业怎么登录？";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "企业登录需要完成认证。", 0.35, "rag", true,
                List.of(citation("chunk:login", "document", 9L, "企业登录")),
                Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "user-gated-low-confidence", question, "账号咨询");

        assertEquals("handoff", result.get("source"));
        assertEquals("HANDOFF", result.get("answerDecision"));
        assertEquals("answerability_gate_low_confidence",
            result.get("fallbackDecision"));
        assertEquals(true, result.get("needsTransfer"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator).handoff(
            10L, "回答依据置信度低于模型调用阈值", "P1");
    }

    @Test
    void recordsSlowResponseWithoutChangingTheAnswer() throws InterruptedException {
        String question = "点签支持电子骑缝章吗";
        ReflectionTestUtils.setField(dialogService, "badCaseSlowResponseMs", 1L);
        when(retrievalService.retrieve(question)).thenAnswer(invocation -> {
            Thread.sleep(20L);
            return new RagRetrievalService.RetrievalResult(
                true, true, "支持网页端和移动端签署。", "ctx", 0.92,
                "direct", true, Collections.emptyList(), Collections.emptyList());
        });

        Map<String, Object> result = dialogService.send(
            "web", "user-slow-response", question, "咨询");

        assertEquals("支持网页端和移动端签署。", result.get("reply"));
        assertEquals(List.of("SLOW_RESPONSE"), result.get("badCaseTriggers"));
        verify(unmatchedQuestionService).recordBadCase(
            eq(question), eq(Set.of("SLOW_RESPONSE")), argThat(context ->
                context.latencyMs() != null && context.latencyMs() >= 1));
    }

    @Test
    void usesBusinessToolBeforeRagRetrieval() {
        BusinessToolOrchestrator.ToolExecutionSummary execution =
            new BusinessToolOrchestrator.ToolExecutionSummary(
                "order.query", "req-1", "FOUND", "local", 12);
        when(businessToolOrchestrator.route(
                10L, "web", "user-order", "查订单 FS202607170001", Collections.emptyList()))
            .thenReturn(new BusinessToolOrchestrator.ToolRoutingResult(
                true, "订单 FS202607170001 当前状态：已发货。", "answered",
                false, null, List.of(execution)));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-order", "查订单 FS202607170001", "订单查询");

        assertEquals("tool", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("订单 FS202607170001 当前状态：已发货。", result.get("reply"));
        assertEquals(false, result.get("needsTransfer"));
        assertEquals("tool", ((Map<?, ?>) result.get("retrieval")).get("decision"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void usesEnabledIntentBeforeRagRetrieval() {
        when(intentService.match("我要申请退款")).thenReturn(Optional.of(
            new IntentService.IntentMatch(
                7L, "退款咨询", "退款", "请提供订单号，我们会协助处理。")));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-refund", "我要申请退款", "退款咨询");

        assertEquals("intent", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("请提供订单号，我们会协助处理。", result.get("reply"));
        assertEquals(7L, ((Map<?, ?>) result.get("intent")).get("id"));
        assertEquals("退款", ((Map<?, ?>) result.get("intent")).get("matchedKeyword"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void recordsContractTypeClarificationAndPreventsRedundantPrompting() throws Exception {
        String question = "你们平台可以签房屋买卖合同吗？";
        when(retrievalService.retrieve(
                eq(question), isNull(), isNull(), eq(KNOWLEDGE_RETRIEVAL_FILTERS),
                anyList(), eq(true)))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                true, false, null,
                "点签电子合同签署流程符合电子签名法要求。", 0.8,
                "partial_rag", true, Collections.emptyList(), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(new ChatResponse(
                "__ANSWER_PARTIAL__\n点签支持电子合同签署。"
                    + "请问您要签署的是商品房买卖合同还是二手房买卖合同？",
                true, "test-model", "test", 30, 20));

        Map<String, Object> result = dialogService.send(
            "web", "property-contract-clarification", question, "合同咨询");

        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) result.get("pendingClarification");
        assertEquals("CONTRACT_TYPE_CAPABILITY", pending.get("intentCode"));
        assertEquals("contractType", pending.get("missingSlot"));
        assertEquals(1, pending.get("expiresAfterTurns"));

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(anyString(), systemPrompt.capture(), isNull());
        assertTrue(systemPrompt.getValue().contains("当前不缺合同类型"));
        assertTrue(systemPrompt.getValue().contains("不得再追问商品房、二手房"));

        ArgumentCaptor<BotMessage> savedMessages = ArgumentCaptor.forClass(BotMessage.class);
        verify(messageService, times(2)).save(savedMessages.capture());
        BotMessage savedAi = savedMessages.getAllValues().stream()
            .filter(message -> "ai".equals(message.getRole()))
            .findFirst().orElseThrow();
        assertEquals("contractType", new ObjectMapper().readTree(savedAi.getMetadata())
            .path("pendingClarification").path("missingSlot").asText());
    }

    @Test
    void doesNotLetBroadIntentOverrideContextualFollowUp() {
        String question = "怎么登录？";
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "我想了解点签电子合同的企业认证流程"),
            message("ai", "企业需要先完成认证。"),
            message("user", question)));
        lenient().when(intentService.match(question)).thenReturn(Optional.of(
            new IntentService.IntentMatch(8L, "登录咨询", "登录", "固定登录回复")));
        Map<String, Object> result = dialogService.send(
            "web", "contextual-login", question, "咨询");

        assertEquals("no_answer", result.get("source"));
        verify(intentService, never()).match(question);
        assertEquals("点签电子合同 " + question, result.get("retrievalQuery"));
    }

    @Test
    void usesOnlyEmotionAsHandoffReasonWhenBusinessToolSucceeded() {
        String question = "真的气死我了！太垃圾了！查订单 FS202607170001";
        BusinessToolOrchestrator.ToolExecutionSummary execution =
            new BusinessToolOrchestrator.ToolExecutionSummary(
                "order.query", "req-angry", "FOUND", "local", 8);
        when(businessToolOrchestrator.route(
                10L, "web", "user-angry-order", question, Collections.emptyList()))
            .thenReturn(new BusinessToolOrchestrator.ToolRoutingResult(
                true, "订单已发货。", "answered", false, null, List.of(execution)));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-angry-order", question, "订单查询");

        assertEquals(true, result.get("needsTransfer"));
        verify(handoffCoordinator).handoff(eq(10L),
            eq("用户情绪高风险（愤怒，连续负面 1 轮）"), eq("P0"));
    }

    @Test
    void compressesOlderConversationContextAndKeepsRecentMessages() {
        BotConversation conversation = new BotConversation();
        conversation.setId(10L);
        List<BotMessage> messages = List.of(
            messageWithId(1L, "user", "我们是南京某企业，主要咨询点签电子合同的使用方式。"),
            messageWithId(2L, "ai", "客服建议先确认企业认证状态。"),
            messageWithId(3L, "user", "我们使用企业套餐，准备从网页端发起合同。"),
            messageWithId(4L, "ai", "客服已说明网页端可以发起合同。"),
            messageWithId(5L, "user", "还想确认签署流程和需要准备的资料。"),
            messageWithId(6L, "ai", "客服给出了签署流程建议。"),
            messageWithId(7L, "user", "如果接收方没有完成认证怎么办？"),
            messageWithId(8L, "ai", "客服建议先补充认证信息。"),
            messageWithId(9L, "user", "当前问题还没有完全解决。"));
        ReflectionTestUtils.setField(dialogService, "contextSummaryEnabled", true);
        ReflectionTestUtils.setField(dialogService, "maxPromptTokens", 100);
        ReflectionTestUtils.setField(dialogService, "contextSummaryMaxChars", 2000);
        when(aiModelService.chatWithModel(anyString(),
                argThat(prompt -> prompt.contains("客服对话摘要器")), isNull()))
            .thenReturn(new ChatResponse(
                "客户身份：企业客户\n咨询产品：点签电子合同\n套餐或版本：企业套餐\n"
                    + "当前问题：认证和签署流程\n已确认信息：网页端可发起合同\n"
                    + "已给出的处理建议：补充认证信息\n仍待确认信息：具体认证入口\n"
                    + "当前未解决事项：接收方认证", true));

        Object result = ReflectionTestUtils.invokeMethod(
            dialogService, "maybeCompressConversation", conversation, messages,
            new LinkedHashSet<String>(), null);

        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(result, "applied"));
        assertTrue(conversation.getContextSummary().contains("点签电子合同"));
        assertEquals(5L, conversation.getSummaryMessageId());
        assertTrue(conversation.getSummaryUpdatedAt() != null);
        verify(conversationService).updateStatus(conversation);
        ArgumentCaptor<String> summaryPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(summaryPrompt.capture(),
            argThat(prompt -> prompt.contains("客服对话摘要器")), isNull());
        assertTrue(summaryPrompt.getValue().contains("南京某企业"));
        assertFalse(summaryPrompt.getValue().contains("当前问题还没有完全解决"));
    }

    @Test
    void keepsExistingContextWhenSummaryModelFails() {
        BotConversation conversation = new BotConversation();
        conversation.setId(10L);
        List<BotMessage> messages = List.of(
            messageWithId(1L, "user", "企业客户咨询点签电子合同的签署流程。"),
            messageWithId(2L, "ai", "客服说明了基础流程。"),
            messageWithId(3L, "user", "还需要确认认证资料。"),
            messageWithId(4L, "ai", "客服建议补充认证资料。"),
            messageWithId(5L, "user", "网页端是否可以发起？"),
            messageWithId(6L, "ai", "客服说可以。"),
            messageWithId(7L, "user", "我还没有得到完整答复。"));
        ReflectionTestUtils.setField(dialogService, "contextSummaryEnabled", true);
        ReflectionTestUtils.setField(dialogService, "maxPromptTokens", 100);
        ReflectionTestUtils.setField(dialogService, "contextSummaryTriggerRatio", 0.01);
        when(aiModelService.chatWithModel(anyString(),
                argThat(prompt -> prompt.contains("客服对话摘要器")), isNull()))
            .thenReturn(new ChatResponse("摘要服务不可用", false));

        Object result = ReflectionTestUtils.invokeMethod(
            dialogService, "maybeCompressConversation", conversation, messages,
            new LinkedHashSet<String>(), null);

        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(result, "applied"));
        assertEquals(null, conversation.getContextSummary());
        assertEquals(null, conversation.getSummaryMessageId());
        assertEquals(null, conversation.getSummaryUpdatedAt());
        verify(conversationService, never()).updateStatus(conversation);
    }

    @Test
    void usesDedicatedSummaryModelWhenConfigured() {
        BotConversation conversation = new BotConversation();
        conversation.setId(10L);
        List<BotMessage> messages = List.of(
            messageWithId(1L, "user", "企业客户咨询点签电子合同的签署流程。"),
            messageWithId(2L, "ai", "客服说明了基础流程。"),
            messageWithId(3L, "user", "还需要确认认证资料。"),
            messageWithId(4L, "ai", "客服建议补充认证资料。"),
            messageWithId(5L, "user", "网页端是否可以发起？"),
            messageWithId(6L, "ai", "客服说可以。"),
            messageWithId(7L, "user", "我还没有得到完整答复。"));
        ReflectionTestUtils.setField(dialogService, "contextSummaryEnabled", true);
        ReflectionTestUtils.setField(dialogService, "maxPromptTokens", 100);
        ReflectionTestUtils.setField(dialogService, "contextSummaryTriggerRatio", 0.01);
        ReflectionTestUtils.setField(dialogService, "contextSummaryModelId", 77L);
        when(aiModelService.chatWithModel(anyString(),
                argThat(prompt -> prompt.contains("客服对话摘要器")), eq(77L)))
            .thenReturn(new ChatResponse("""
                客户身份：企业客户
                咨询产品：点签电子合同
                套餐或版本：未确认
                当前问题：确认认证资料
                已确认信息：已了解基础流程
                已给出的处理建议：补充认证资料
                仍待确认信息：认证资料明细
                当前未解决事项：网页端发起入口
                """.strip(), true));

        Object result = ReflectionTestUtils.invokeMethod(
            dialogService, "maybeCompressConversation", conversation, messages,
            new LinkedHashSet<String>(), 4L);

        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(result, "applied"));
        verify(aiModelService).chatWithModel(anyString(),
            argThat(prompt -> prompt.contains("客服对话摘要器")), eq(77L));
    }

    @Test
    void rejectsSummaryThatDoesNotContainEveryRequiredField() {
        BotConversation conversation = new BotConversation();
        conversation.setId(10L);
        conversation.setContextSummary("原有摘要");
        conversation.setSummaryMessageId(1L);
        List<BotMessage> messages = List.of(
            messageWithId(1L, "user", "企业客户咨询点签电子合同。"),
            messageWithId(2L, "ai", "客服说明了基础流程。"),
            messageWithId(3L, "user", "还需要确认认证资料。"),
            messageWithId(4L, "ai", "客服建议补充认证资料。"),
            messageWithId(5L, "user", "网页端是否可以发起？"),
            messageWithId(6L, "ai", "客服说可以。"),
            messageWithId(7L, "user", "我还没有得到完整答复。"));
        ReflectionTestUtils.setField(dialogService, "contextSummaryEnabled", true);
        ReflectionTestUtils.setField(dialogService, "maxPromptTokens", 100);
        ReflectionTestUtils.setField(dialogService, "contextSummaryTriggerRatio", 0.01);
        when(aiModelService.chatWithModel(anyString(),
                argThat(prompt -> prompt.contains("客服对话摘要器")), isNull()))
            .thenReturn(new ChatResponse(
                "客户身份：企业客户\n当前问题：认证资料", true));

        Object result = ReflectionTestUtils.invokeMethod(
            dialogService, "maybeCompressConversation", conversation, messages,
            new LinkedHashSet<String>(), null);

        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(result, "applied"));
        assertEquals("原有摘要", conversation.getContextSummary());
        assertEquals(1L, conversation.getSummaryMessageId());
        verify(conversationService, never()).updateStatus(conversation);
    }

    @Test
    void asksForMissingContractTypeBeforeKnowledgeRetrieval() {
        Map<String, Object> result = dialogService.send(
            "web", "missing-contract-type", "点签支持签合同吗？", "合同咨询");

        assertEquals("decision", result.get("source"));
        assertEquals("clarify", result.get("answerStatus"));
        assertEquals("CLARIFY", result.get("answerDecision"));
        assertEquals("CLARIFY",
            ((Map<?, ?>) result.get("serviceDecision")).get("decision"));
        assertEquals(List.of("contractType"),
            ((Map<?, ?>) result.get("serviceDecision")).get("missingSlots"));
        assertEquals(1,
            ((Map<?, ?>) result.get("pendingClarification")).get("attempt"));
        assertEquals(2,
            ((Map<?, ?>) result.get("pendingClarification")).get("maxAttempts"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void asksWhichAccountOperationBeforeKnowledgeRetrieval() {
        Map<String, Object> result = dialogService.send(
            "web", "missing-account-action", "账号怎么弄？", "账号咨询");

        assertEquals("decision", result.get("source"));
        assertEquals("missing_account_action", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("注册、登录、实名认证"));
        assertEquals("accountAction",
            ((Map<?, ?>) result.get("pendingClarification")).get("missingSlot"));
        verify(retrievalService, never()).retrieve(anyString());
    }

    @Test
    void keepsConfiguredIntentPriorityForAHighFrequencyQuestion() {
        when(intentService.match("账号怎么弄？")).thenReturn(Optional.of(
            new IntentService.IntentMatch(
                9L, "账号帮助", "账号", "请从登录页进入账号帮助。")));

        Map<String, Object> result = dialogService.send(
            "web", "configured-account-intent", "账号怎么弄？", "账号咨询");

        assertEquals("intent", result.get("source"));
        assertEquals("请从登录页进入账号帮助。", result.get("reply"));
        verify(retrievalService, never()).retrieve(anyString());
    }

    @Test
    void resumesTheOriginalIntentAfterTheCustomerSuppliesAContractType()
            throws Exception {
        String resolvedQuery = "点签 是否支持签署 劳动合同";
        BotMessage clarification = message("ai", "请问您具体想签署哪一种合同？");
        clarification.setMetadata(new ObjectMapper().writeValueAsString(Map.of(
            "pendingClarification", Map.of(
                "intentCode", "CONTRACT_TYPE_CAPABILITY",
                "missingSlot", "contractType",
                "queryTemplate", "点签 是否支持签署 {contractType}",
                "question", "请问您具体想签署哪一种合同？",
                "attempt", 1,
                "maxAttempts", 2))));
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "点签支持签合同吗？"), clarification,
            message("user", "劳动合同")));
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        when(retrievalService.retrieve(resolvedQuery)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.0, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "contract-type-follow-up", "劳动合同", "合同咨询");

        assertEquals(resolvedQuery, result.get("retrievalQuery"));
        assertEquals(true, result.get("clarificationStateConsumed"));
        assertEquals("decision_metadata", result.get("clarificationResolutionSource"));
        verify(retrievalService).retrieve(resolvedQuery);
    }

    @Test
    void repeatsAClarificationOnlyOnceForAnInvalidReply() throws Exception {
        BotMessage clarification = message("ai", "请问您具体想签署哪一种合同？");
        clarification.setMetadata(new ObjectMapper().writeValueAsString(Map.of(
            "pendingClarification", Map.of(
                "intentCode", "CONTRACT_TYPE_CAPABILITY",
                "missingSlot", "contractType",
                "queryTemplate", "点签 是否支持签署 {contractType}",
                "question", "请问您具体想签署哪一种合同？",
                "attempt", 1,
                "maxAttempts", 2))));
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "点签支持签合同吗？"), clarification,
            message("user", "不知道")));

        Map<String, Object> result = dialogService.send(
            "web", "contract-type-retry", "不知道", "合同咨询");

        assertEquals("CLARIFY", result.get("answerDecision"));
        assertEquals(2,
            ((Map<?, ?>) result.get("pendingClarification")).get("attempt"));
        verify(retrievalService, never()).retrieve(anyString());
    }

    @Test
    void replacesCrmWithErpFromPersistedConversationState() {
        BotConversation conversation = conversationService.getOrCreate(
            "web", "state-erp-follow-up", "咨询");
        conversation.setDialogStateVersion(2L);
        conversation.setDialogState("""
            {"schemaVersion":1,"status":"ACTIVE",\
             "activeIntent":"SYSTEM_INTEGRATION",\
             "entities":{"business_system":"CRM"},"missingSlots":[],\
             "standaloneQuery":"点签电子签章是否支持通过API集成到CRM系统？",\
             "pending":null,"clarificationAttempts":0,"remainingTurns":3}
            """);
        String question = "那我们的ERP系统呢？";
        String resolvedQuery = "点签电子签章是否支持通过API集成到ERP系统？";
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "CRM客户管理系统可以用吗？"),
            message("ai", "点签支持通过API接入业务系统。"),
            message("user", question)));
        when(retrievalService.retrieve(resolvedQuery)).thenReturn(
            retrieval("点签提供API，可对接ERP等业务系统。", 0.90));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(new ChatResponse(
                "点签支持通过API对接ERP系统。", true,
                "answer-model", "test", 30, 12));

        Map<String, Object> result = dialogService.send(
            "web", "state-erp-follow-up", question, "咨询");

        assertEquals("rag_ai", result.get("source"));
        assertEquals(resolvedQuery, result.get("retrievalPrimaryQuery"));
        assertEquals("SYSTEM_INTEGRATION",
            ((Map<?, ?>) result.get("nlpIntent")).get("intentCode"));
        assertEquals("semantic_context_resolution",
            result.get("clarificationResolutionSource"));
        verify(intentUnderstandingService).understand(
            eq(question), anyList(), isNull(), argThat(state ->
                "SYSTEM_INTEGRATION".equals(state.get("active_intent"))));
    }

    @Test
    void completeErpQuestionClearsExhaustedPendingClarification() throws Exception {
        BotConversation conversation = conversationService.getOrCreate(
            "web", "state-new-erp", "咨询");
        conversation.setDialogStateVersion(5L);
        conversation.setDialogState(waitingContextState(2));
        when(conversationService.getById(10L)).thenReturn(conversation);
        when(conversationService.updateDialogState(
                eq(conversation), anyString(), eq(5L))).thenReturn(true);
        String question = "点签可以嵌入ERP系统吗？";
        String resolvedQuery = "点签电子签章是否支持通过API集成到ERP系统？";
        when(retrievalService.retrieve(resolvedQuery)).thenReturn(
            retrieval("点签提供API，可嵌入ERP系统。", 0.91));
        when(aiModelService.chatWithModel(anyString(), anyString(), isNull()))
            .thenReturn(new ChatResponse(
                "点签可以通过API嵌入ERP系统。", true,
                "answer-model", "test", 30, 12));

        Map<String, Object> result = dialogService.send(
            "web", "state-new-erp", question, "咨询");

        assertEquals("ANSWER",
            ((Map<?, ?>) result.get("serviceDecision")).get("decision"));
        assertEquals(resolvedQuery, result.get("retrievalPrimaryQuery"));
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
        ArgumentCaptor<String> stateJson = ArgumentCaptor.forClass(String.class);
        verify(conversationService).updateDialogState(
            eq(conversation), stateJson.capture(), eq(5L));
        JsonNode persisted = new ObjectMapper().readTree(stateJson.getValue());
        assertEquals("ACTIVE", persisted.path("status").asText());
        assertTrue(persisted.path("pending").isNull());
        assertEquals("ERP", persisted.path("entities").path("business_system").asText());
    }

    @Test
    void productOverviewQuestionClearsPendingWithoutClarificationHandoff() {
        BotConversation conversation = conversationService.getOrCreate(
            "web", "state-product-overview", "咨询");
        conversation.setDialogStateVersion(2L);
        conversation.setDialogState(waitingContextState(2));

        Map<String, Object> result = dialogService.send(
            "web", "state-product-overview", "你们是干什么的？", "咨询");

        assertEquals("ANSWER",
            ((Map<?, ?>) result.get("serviceDecision")).get("decision"));
        assertFalse("clarify".equals(result.get("answerStatus")));
        assertFalse(result.containsKey("pendingClarification"));
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
    }

    private String waitingContextState(int attempt) {
        return """
            {"schemaVersion":1,"status":"WAITING_FOR_SLOT",\
             "activeIntent":"UNKNOWN","entities":{},"missingSlots":["context"],\
             "standaloneQuery":"这个怎么操作",\
             "pending":{"intentCode":"UNKNOWN","missingSlot":"context",\
               "queryTemplate":"{context}","question":"请补充具体场景",\
               "attempt":%d,"maxAttempts":2,"reasonCode":"unresolved_reference",\
               "sourceQuestion":"这个怎么操作"},\
             "clarificationAttempts":%d,"remainingTurns":1}
            """.formatted(attempt, attempt);
    }

    private Map<String, Object> citation(String id, String type, Long sourceId, String title) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ref", 1);
        value.put("id", id);
        value.put("sourceType", type);
        value.put("sourceId", sourceId);
        value.put("title", title);
        value.put("score", 1.0);
        value.put("snippet", "测试片段");
        return value;
    }

    private RagRetrievalService.RetrievalResult retrieval(String context, double confidence) {
        return new RagRetrievalService.RetrievalResult(
            true, false, null, context, confidence, "rag", true,
            Collections.emptyList(), Collections.emptyList());
    }

    private BotMessage message(String role, String content) {
        BotMessage message = new BotMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private IntentUnderstandingService.Understanding knowledgeUnderstanding(
            String intentCode, String standaloneQuery, boolean contextDependent) {
        return new IntentUnderstandingService.Understanding(
            true, true, IntentUnderstandingService.Route.KNOWLEDGE,
            intentCode, standaloneQuery, Map.of("product", "点签电子合同"), List.of(),
            contextDependent, 0.91, "semantic_understanding",
            "intent-model", "test", 20, 10, 12L);
    }

    private BotMessage messageWithId(Long id, String role, String content) {
        BotMessage message = message(role, content);
        message.setId(id);
        return message;
    }
}
