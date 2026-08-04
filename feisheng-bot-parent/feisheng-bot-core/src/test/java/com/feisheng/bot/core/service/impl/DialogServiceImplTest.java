package com.feisheng.bot.core.service.impl;

import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.dto.SafetyResult;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotAiReplyLogMapper;
import com.feisheng.bot.core.service.ConversationServiceImpl;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
            aiReplyLogMapper, retrievalService, unmatchedQuestionService,
            businessToolOrchestrator, intentService,
            new NlpIntentClassifier(),
            new SensitiveDataService("18689633999"),
            handoffCoordinator, new EmotionService(), replyAttachmentService,
            new ContextualQueryResolver(), new ObjectMapper());
        ReflectionTestUtils.setField(dialogService, "systemPromptFull", "rag-system-prompt");
        ReflectionTestUtils.setField(dialogService, "noAnswerReply", "知识库暂无答案");
        ReflectionTestUtils.setField(dialogService, "outOfScopeReply", "请联系总部售后");
        ReflectionTestUtils.setField(dialogService, "outOfScopeKeywords",
            "CA锁,实体锁,UKey,U-Key,安全控件,守信签,总部电子签章,翔晟电子签章");
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", true);
        ReflectionTestUtils.setField(dialogService, "lowConfidenceThreshold", 0.55);
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
        assertEquals("知识库暂无答案", result.get("reply"));
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
    void routesLowConfidenceOutOfScopeQuestionWithoutCallingTheModel() {
        ReflectionTestUtils.setField(dialogService, "transferOnNoAnswer", false);
        when(retrievalService.retrieve("实体锁怎么续期")).thenReturn(
            new RagRetrievalService.RetrievalResult(
                true, false, null, "弱相关知识", 0.52, "rag", true,
                List.of(citation("chunk:12", "document", 12L, "点签说明")),
                Collections.emptyList()));

        Map<String, Object> result = dialogService.send(
            "wecom", "user-out-of-scope", "实体锁怎么续期", "咨询");

        assertEquals("请联系总部售后", result.get("reply"));
        assertEquals("out_of_scope", result.get("source"));
        assertEquals("out_of_scope", result.get("answerStatus"));
        assertEquals(true, result.get("lowConfidence"));
        assertEquals(false, result.get("needsTransfer"));
        assertEquals(Collections.emptyList(), result.get("citations"));
        verify(unmatchedQuestionService, never()).record(anyString());
        verify(aiModelService, never()).chatWithModel(anyString(), anyString(), any());
        verify(handoffCoordinator, never()).handoff(any(), anyString(), anyString());
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

        assertEquals("知识库暂无答案", result.get("reply"));
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

        assertEquals("知识库暂无答案", result.get("reply"));
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

    private BotMessage message(String role, String content) {
        BotMessage message = new BotMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
