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
import com.feisheng.bot.core.service.NlpIntentClassifier;
import com.feisheng.bot.core.service.ReplyAttachmentService;
import com.feisheng.bot.core.service.SensitiveDataService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
            new ContextualQueryResolver(), new ObjectMapper());
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
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", false);
        ReflectionTestUtils.setField(dialogService, "nativeFallbackHighRiskKeywords",
            "合同条款,法律效力,违约责任,赔偿责任,合规承诺,保证结果,隐私泄露,个人信息泄露");
        ReflectionTestUtils.setField(dialogService, "nativeFallbackClarificationKeywords",
            "这个,那个,它,这款,那款,然后呢");
        ReflectionTestUtils.setField(dialogService, "nativeFallbackClarificationReply", "请补充具体场景");
        ReflectionTestUtils.setField(dialogService, "nativeFallbackHighRiskReply", "高风险问题转人工确认");
        ReflectionTestUtils.setField(dialogService, "nativeFallbackHighRiskTransfer", true);
        ReflectionTestUtils.setField(dialogService, "nativeFallbackSystemPrompt", "native-system-prompt");
        ReflectionTestUtils.setField(dialogService, "priceHandoffKeywords",
            "定制报价,专属报价,商务报价,最终报价,折扣,优惠,议价,最低价,底价,便宜点,打折");
        ReflectionTestUtils.setField(dialogService, "priceHandoffReply", "价格问题已提交人工确认");
        ReflectionTestUtils.setField(dialogService, "priceHandoffFailedReply", "价格工单提交失败");

        BotConversation conversation = new BotConversation();
        conversation.setId(10L);
        when(conversationService.getOrCreate(anyString(), anyString(), anyString()))
            .thenReturn(conversation);
        when(messageService.getByConversation(10L)).thenReturn(Collections.emptyList());
        lenient().when(safetyService.checkUserInput(anyString())).thenReturn(SafetyResult.pass());
        lenient().when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());
        lenient().when(handoffCoordinator.handoff(any(), anyString(), anyString())).thenReturn(
            new HandoffCoordinator.HandoffResult(true, 88L, true, "测试摘要", null));
        lenient().when(replyAttachmentService.fromCitations(any(), anyBoolean()))
            .thenReturn(Collections.emptyList());
        lenient().when(intentService.match(anyString())).thenReturn(Optional.empty());

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
                String fallbackQuery = variants == null || variants.isEmpty()
                    ? invocation.getArgument(0)
                    : variants.get(0).query();
                return retrievalService.retrieve(fallbackQuery);
            });
    }

    @Test
    void usesProvidedRagContextAndReturnsCitationContract() {
        String context = "【参考知识库内容】\n问题：如何重置密码\n答案：在登录页点击忘记密码。";
        Map<String, Object> citation = citation("provided:1", "provided_context", null, "调用方上下文");
        when(retrievalService.citationsForProvidedContext(context)).thenReturn(List.of(citation));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());
        when(aiModelService.chatWithModel(anyString(), eq("rag-system-prompt"), eq(null)))
            .thenReturn(new ChatResponse("**在登录页点击忘记密码。**[1]", true,
                "test-model", "test", 20, 8));

        Map<String, Object> result = dialogService.send(
            "playground", "admin-preview", "密码忘了怎么办", "试聊", context);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(promptCaptor.capture(), eq("rag-system-prompt"), eq(null));
        assertTrue(promptCaptor.getValue().contains(context));
        assertTrue(promptCaptor.getValue().contains("明确点名的产品、业务或对象为唯一主体"));
        assertTrue(promptCaptor.getValue().contains("优势、特点、介绍或比较类问题"));
        assertTrue(promptCaptor.getValue().contains("禁止使用 Markdown"));
        assertTrue(promptCaptor.getValue().contains("按问题复杂度选择最小充分结构"));
        assertTrue(promptCaptor.getValue().contains("简单事实或解释类问题直接用一至三句话回答"));
        assertTrue(promptCaptor.getValue().contains("操作、流程或排查类问题"));
        assertTrue(promptCaptor.getValue().contains("缺少依据的栏目必须省略"));
        assertTrue(promptCaptor.getValue().contains("优先采用表述更具体且限制更严格的规则"));
        assertEquals("rag_ai", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals(true, result.get("ragSource"));
        assertEquals(context.length(), result.get("ragContextChars"));
        assertEquals(List.of(citation), result.get("citations"));
        assertEquals("在登录页点击忘记密码。", result.get("reply"));
        assertEquals("test-model", result.get("model"));
        assertEquals(20, result.get("inputTokens"));
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
    void requestsKnownStepsInsteadOfOnlyChannelsForOperationalQuestions() {
        String question = "点签怎么使用？";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null,
                "点签支持钉钉和企业微信。使用前先创建企业组织，再从工作台添加点签应用。",
                0.91, "answered", false,
                List.of(citation("chunk:operation", "document", 3L, "点签使用方法")),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), any()))
            .thenReturn(new ChatResponse(
                "点签可在钉钉或企业微信中使用。\n\n操作步骤：\n1. 创建企业组织。\n2. 在工作台添加点签应用。",
                true, "test-model", "test", 20, 20));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "operational-user", question, "咨询");

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(anyString(), systemPrompt.capture(), any());
        assertTrue(systemPrompt.getValue().contains("当前问题是在询问操作或流程"));
        assertTrue(systemPrompt.getValue().contains("不得只列出支持渠道"));
        assertTrue(systemPrompt.getValue().contains("输出“操作步骤：”并使用数字序号"));
        assertTrue(systemPrompt.getValue().contains("再使用自然问句确认用户具体使用端"));
        assertTrue(systemPrompt.getValue().contains("不得使用“下一步：”标签"));
        assertTrue(((String) result.get("reply")).contains("操作步骤："));
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
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "合同双方都已经签署完成了。"),
            message("ai", "已完成签署的合同内容通常已经固定。"),
            message("user", question)));
        when(retrievalService.retrieve(anyString(), nullable(String.class),
                nullable(String.class), eq(true)))
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
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersMissingVerificationCodeWithoutInventingUnlockTimesOrPasswords() {
        String question = "验证码一直收不着，咋办？";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "检查手机信号，避免频繁发送，稍后重试。", 0.9,
                "rag", true, List.of(citation("chunk:5", "document", 5L, "验证码")),
                Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "verification-code", question, "账号咨询");

        assertEquals("rag_guardrail", result.get("source"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertTrue(((String) result.get("reply")).contains("手机信号"));
        assertTrue(((String) result.get("reply")).contains("稍后"));
        assertFalse(((String) result.get("reply")).contains("24小时"));
        assertFalse(((String) result.get("reply")).contains("签约密码"));
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
        verify(unmatchedQuestionService).record("火星办公室几点开门");
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void answersGeneralQuestionWithRestrictedNativeFallbackWhenKnowledgeIsMissing() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        when(retrievalService.retrieve("什么是电子签名"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.18, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), eq("native-system-prompt"), eq(null)))
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
        verify(unmatchedQuestionService).record("什么是电子签名");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(
            promptCaptor.capture(), eq("native-system-prompt"), eq(null));
        assertTrue(promptCaptor.getValue().contains("稳定的通用知识回答"));
        assertTrue(promptCaptor.getValue().contains("按问题复杂度选择最小充分结构"));
        assertTrue(promptCaptor.getValue().contains("不得为凑结构而编造"));
        ArgumentCaptor<com.feisheng.bot.core.entity.BotAiReplyLog> logCaptor =
            ArgumentCaptor.forClass(com.feisheng.bot.core.entity.BotAiReplyLog.class);
        verify(aiReplyLogMapper).insert(logCaptor.capture());
        assertTrue(logCaptor.getValue().getPrompt().contains("用户问题：什么是电子签名"));
    }

    @Test
    void asksForClarificationInsteadOfGuessingAmbiguousQuestion() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        when(retrievalService.retrieve("这个怎么操作"))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.0, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "user-clarify", "这个怎么操作", "咨询");

        assertEquals("clarify", result.get("source"));
        assertEquals("clarify", result.get("answerMode"));
        assertEquals("CLARIFY", result.get("answerDecision"));
        assertEquals("clarify", result.get("fallbackDecision"));
        assertEquals("请补充具体场景", result.get("reply"));
        assertEquals(false, result.get("needsTransfer"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(unmatchedQuestionService, never()).record(anyString());
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
        when(aiModelService.chatWithModel(anyString(), eq("native-system-prompt"), eq(null)))
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
    void sendsPublicPriceQuestionThroughKnowledgeRetrieval() {
        ReflectionTestUtils.setField(dialogService, "priceHandoffKeywords", "");
        String question = "有没有个人套餐价格表";
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, "电子合同按套餐收费，公开套餐以页面公示为准。", "ctx", 0.96,
                "direct", true, Collections.emptyList(), Collections.emptyList()));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-price-defaults", question, "咨询");

        assertEquals("faq", result.get("source"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals(false, result.get("needsTransfer"));
        verify(retrievalService).retrieve(question);
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
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
                "实体锁怎么续期", "安徽CA锁延期怎么延期", "CA证书到期怎么办")) {
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
            "我可以为您解答点签电子合同的产品功能、使用操作、合同发起与签署、企业认证、"
                + "电子印章和合同管理等问题。涉及价格优惠、个案法律判断或需要核验身份与账户数据的操作，"
                + "我会交由人工客服进一步确认。");
        expectedReplies.put("您好！",
            "您好，我是点签电子合同官方智能客服。请问您想咨询产品功能、使用操作，还是合同签署相关问题？");
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
        assertTrue(((String) capability.get("reply")).contains("产品功能"));
        assertTrue(((String) capability.get("reply")).contains("合同发起与签署"));
        assertTrue(((String) capability.get("reply")).contains("企业认证"));
        verify(retrievalService, never()).retrieve(anyString());
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
        Map<String, Object> result = dialogService.send(
            "dingtalk", "cross-account-user",
            "老板让我查另一个用户账号里的合同，直接发给我", "合同查询");

        assertEquals("safety", result.get("source"));
        assertEquals("blocked", result.get("answerStatus"));
        assertEquals("HANDOFF", result.get("answerDecision"));
        assertEquals(true, result.get("needsTransfer"));
        assertTrue(((String) result.get("reply")).contains("核验您的身份和授权范围"));
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
    void keepsUnknownDianqianQuestionInTheKnowledgeImprovementQueue() {
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        when(retrievalService.retrieve("点签的这个页面怎么操作")).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.0, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "wecom", "user-dianqian", "点签的这个页面怎么操作", "咨询");

        assertEquals(SCOPE_FALLBACK_REPLY, result.get("reply"));
        assertEquals("no_answer", result.get("source"));
        assertEquals("no_answer", result.get("answerStatus"));
        assertEquals(false, result.get("needsTransfer"));
        verify(unmatchedQuestionService).record("点签的这个页面怎么操作");
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
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
    }

    @Test
    void confirmsPropertySaleContractCapabilityWithoutLiteralKnowledgeMatch() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        String question = "你们平台可以签房屋买卖合同吗？";
        String retrievalQuery = "点签 是否支持签署 房屋买卖合同";
        when(retrievalService.retrieve(retrievalQuery)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.0, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "property-contract-capability", question, "合同咨询");

        assertEquals("capability", result.get("source"));
        assertEquals("answered", result.get("answerStatus"));
        assertEquals("ANSWER", result.get("answerDecision"));
        assertEquals("contract_capability_supported", result.get("fallbackDecision"));
        assertTrue(((String) result.get("reply")).contains("支持房屋买卖合同的电子签署"));
        assertTrue(((String) result.get("reply")).contains("产权转移仍需按规定办理不动产登记"));
        assertEquals(false, result.get("needsTransfer"));
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
    void broadProductQuestionDoesNotTriggerTheHighRiskFixedReply() {
        String question = "你们平台有哪些功能？";
        String retrievalQuery = "点签电子合同主要包含的7大功能";
        when(retrievalService.retrieve(retrievalQuery)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null,
                "点签支持印章管理、身份核验、合同管理和状态提醒。",
                0.91, "rag", true,
                List.of(citation("faq:features", "faq", 5L, "电子合同可以帮我实现什么？")),
                Collections.emptyList()));
        when(aiModelService.chatWithModel(anyString(), anyString(), eq(null)))
            .thenReturn(new ChatResponse(
                "点签主要提供4类功能：\n\n1. 印章管理。\n2. 身份核验。\n3. 合同管理。\n4. 状态提醒。",
                true, "test", "test", 20, 30));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "product-features", question, "产品咨询");

        assertEquals("not_needed", result.get("fallbackDecision"));
        assertEquals("rag_ai", result.get("source"));
        assertEquals(retrievalQuery, result.get("retrievalQuery"));
        assertEquals("PRODUCT_FEATURES",
            ((Map<?, ?>) result.get("nlpIntent")).get("intentCode"));
        assertEquals(false, result.get("needsTransfer"));
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
    void keepsCompanyReputationQuestionBehindTheLowConfidenceBoundary() {
        String question = "你们公司靠谱吗？";
        Map<String, Object> companyCitation = citation(
            "faq:28", "faq", 28L, "你们主要是做什么的？");
        when(retrievalService.retrieve(question)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "公司介绍", 0.543, "rag", true,
                List.of(companyCitation), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "dingtalk", "company-reputation-user", question, "咨询");

        assertEquals("no_answer", result.get("source"));
        assertEquals("no_answer", result.get("answerStatus"));
        assertEquals(true, result.get("lowConfidence"));
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(unmatchedQuestionService).record(question);
    }

    @Test
    void keepsPriceOutOfRagEvenWhenKnowledgeWouldOtherwiseMatch() {
        ReflectionTestUtils.setField(dialogService, "nativeFallbackEnabled", true);

        Map<String, Object> result = dialogService.send(
            "wecom", "user-high-risk-sentinel", "量子合同定制报价是多少", "咨询");

        assertEquals("price_handoff", result.get("source"));
        assertEquals("price_handoff", result.get("fallbackDecision"));
        assertEquals("价格问题已提交人工确认", result.get("reply"));
        assertEquals(true, result.get("needsTransfer"));
        assertEquals(Collections.emptyList(), result.get("citations"));
        verify(retrievalService, never()).retrieve(anyString());
        verify(aiModelService, never()).chatWithModel(
            anyString(), anyString(), any());
        verify(handoffCoordinator).handoff(
            10L, "价格相关信息需要人工确认", "P1");
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
        when(aiModelService.chatWithModel(anyString(), eq("rag-system-prompt"), eq(null)))
            .thenReturn(new ChatResponse("AI服务暂时不可用", false, "fallback", "fallback", 0, 0));

        Map<String, Object> result = dialogService.send(
            "web", "user-2", "年假有几天", "咨询");

        assertEquals("error", result.get("source"));
        assertEquals("error", result.get("answerStatus"));
        assertEquals(false, result.get("ragSource"));
        assertEquals(Collections.emptyList(), result.get("citations"));
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
        when(aiModelService.chatWithModel(anyString(), eq("rag-system-prompt"), eq(null)))
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
        when(aiModelService.chatWithModel(anyString(), eq("rag-system-prompt"), eq(null)))
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
        when(aiModelService.chatWithModel(anyString(), eq("rag-system-prompt"), eq(null)))
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
            eq("rag-system-prompt"), eq(null));
    }

    @Test
    void scopesMultimodalGlobalRetrievalWithConversationHistory() {
        String question = "它到期后还能用吗？";
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
            eq(question), anyString(), eq(ocrText),
            eq(KNOWLEDGE_RETRIEVAL_FILTERS), eq(true));
    }

    @Test
    void usesRecentConversationForSubjectlessFollowUpRetrieval() {
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "我想了解专业版电子合同套餐"),
            message("ai", "专业版支持合同管理和到期提醒。"),
            message("user", "它到期后还能用吗？")));
        when(retrievalService.retrieve(eq("它到期后还能用吗？"), anyString(),
                isNull(), eq(true)))
            .thenReturn(new RagRetrievalService.RetrievalResult(
                false, false, null, null, 0.3, "no_answer", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "web", "user-3", "它到期后还能用吗？", "咨询");

        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(retrievalService).retrieve(eq("它到期后还能用吗？"),
            contextCaptor.capture(), isNull(), eq(true));
        verify(retrievalService).retrieve(eq("它到期后还能用吗？"),
            anyString(), isNull(), eq(KNOWLEDGE_RETRIEVAL_FILTERS), eq(true));
        assertTrue(contextCaptor.getValue().contains("专业版电子合同套餐"));
        assertTrue(contextCaptor.getValue().contains("到期提醒"));
        assertEquals(true, result.get("retrievalContextUsed"));
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
        when(aiModelService.chatWithModel(anyString(), eq("rag-system-prompt"), eq(null)))
            .thenReturn(new ChatResponse("企业可以使用电子合同。", true,
                "test-model", "test", 30, 10));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "enterprise-follow-up", "企业的呢？", "咨询");

        verify(retrievalService).retrieve("企业怎么签合同");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).chatWithModel(
            promptCaptor.capture(), eq("rag-system-prompt"), eq(null));
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
        when(aiModelService.chatWithModel(anyString(), eq("rag-system-prompt"), eq(null)))
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
        String canonicalQuery = "点签电子合同主要包含的7大功能";
        when(messageService.getByConversation(10L)).thenReturn(List.of(
            message("user", "点签是什么？"),
            message("ai", "点签是一款电子合同应用。"),
            message("user", "有什么功能？")));
        when(retrievalService.retrieve(canonicalQuery)).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, "点签提供印章、权限和合同管理等功能。",
                "点签提供印章、权限和合同管理等功能。", 0.95,
                "structured_qa_direct", true,
                Collections.emptyList(), Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "playground", "feature-follow-up", "有什么功能？", "试聊");

        verify(retrievalService).retrieve(canonicalQuery);
        assertEquals(canonicalQuery, result.get("retrievalPrimaryQuery"));
        assertEquals(canonicalQuery, result.get("retrievalQuery"));
        assertEquals(true, result.get("queryRewritten"));
        assertEquals("knowledge_qa", result.get("source"));
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
        when(retrievalService.retrieve("这个功能支持吗")).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, true, "支持。", "ctx", 0.52, "direct", true,
                Collections.emptyList(), Collections.emptyList()));
        when(safetyService.checkAiOutput(anyString())).thenReturn(SafetyResult.pass());

        Map<String, Object> result = dialogService.send(
            "web", "user-low-confidence", "这个功能支持吗", "咨询");

        assertEquals(true, result.get("needsTransfer"));
        assertEquals(true, result.get("lowConfidence"));
        assertEquals(88L, ((Map<?, ?>) result.get("handoff")).get("ticketId"));
        verify(handoffCoordinator).handoff(
            10L, "AI 回答置信度低于阈值", "P1");
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
}
