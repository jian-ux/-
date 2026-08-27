package com.feisheng.bot.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.entity.BotCustomer;
import com.feisheng.bot.core.mapper.BotCustomerMapper;
import com.feisheng.bot.core.service.impl.AiModelServiceImpl;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerProfileServiceTest {
    @Mock private BotCustomerMapper mapper;
    @Mock private AiModelServiceImpl aiModelService;

    @Test
    void ignoresPlaygroundSessionsWithoutLoadingOrCreatingCustomerProfiles() {
        CustomerProfileService service = new CustomerProfileService(mapper, new ObjectMapper());

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            " playground ", "trial-user", "我们是南京测试有限公司，我是管理员。");

        assertFalse(result.updated());
        assertTrue(result.facts().isEmpty());
        verify(mapper, never()).selectOne(any());
        verify(mapper, never()).insert(any(BotCustomer.class));
        verify(mapper, never()).updateById(any(BotCustomer.class));
    }

    @Test
    void storesOnlyExplicitStableCustomerFacts() {
        BotCustomer customer = new BotCustomer();
        customer.setId(9L);
        when(mapper.selectOne(any())).thenReturn(customer);
        CustomerProfileService service = new CustomerProfileService(mapper, new ObjectMapper());

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            "dingtalk", "user-1",
            "我们是南京测试有限公司，我是管理员，使用的是点签电子合同，版本是企业版，主要在网页端使用。合同怎么发起？");

        assertTrue(result.updated());
        assertEquals("南京测试有限公司", result.facts().get("company").get("value"));
        assertEquals("管理员", result.facts().get("role").get("value"));
        assertEquals("点签电子合同", result.facts().get("product").get("value"));
        assertEquals("企业版", result.facts().get("plan").get("value"));
        assertEquals("网页端", result.facts().get("channel").get("value"));
        ArgumentCaptor<BotCustomer> captor = ArgumentCaptor.forClass(BotCustomer.class);
        verify(mapper).updateById(captor.capture());
        assertTrue(captor.getValue().getProfileJson().contains("user_explicit"));
        assertTrue(captor.getValue().getProfileJson().contains("expiresAt"));
    }

    @Test
    void extractsCommonSpokenVariantsAndMultipleFactsFromOneMessage() {
        when(mapper.selectOne(any())).thenReturn(null);
        CustomerProfileService service = new CustomerProfileService(mapper, new ObjectMapper());

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            "dingtalk", "user-variants",
            "我代表星河科技，负责合同管理，我们用的是点签企业版，平时用电脑操作。合同怎么发起？");

        assertEquals("星河科技", result.facts().get("company").get("value"));
        assertEquals("合同管理", result.facts().get("role").get("value"));
        assertEquals("点签电子合同", result.facts().get("product").get("value"));
        assertEquals("企业版", result.facts().get("plan").get("value"));
        assertEquals("PC端", result.facts().get("channel").get("value"));
    }

    @Test
    void normalizesBrowserAndPlanAliases() {
        when(mapper.selectOne(any())).thenReturn(null);
        CustomerProfileService service = new CustomerProfileService(mapper, new ObjectMapper());

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            "dingtalk", "user-aliases",
            "公司叫星河科技，产品是点签，套餐是企业套餐，通常通过浏览器登录。");

        assertEquals("星河科技", result.facts().get("company").get("value"));
        assertEquals("点签电子合同", result.facts().get("product").get("value"));
        assertEquals("企业版", result.facts().get("plan").get("value"));
        assertEquals("网页端", result.facts().get("channel").get("value"));
    }

    @Test
    void ignoresNegatedFacts() {
        when(mapper.selectOne(any())).thenReturn(null);
        CustomerProfileService service = new CustomerProfileService(mapper, new ObjectMapper());

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            "dingtalk", "user-negative", "我不是管理员，不使用企业版，也不用手机端。");

        assertTrue(result.facts().isEmpty());
        verify(mapper, never()).insert(any(BotCustomer.class));
        verify(mapper, never()).updateById(any(BotCustomer.class));
    }

    @Test
    void preservesConflictingFactUntilCustomerExplicitlyChangesIt() {
        BotCustomer customer = new BotCustomer();
        customer.setId(10L);
        customer.setProfileJson("{\"plan\":{\"value\":\"企业版\"}}");
        when(mapper.selectOne(any())).thenReturn(customer);
        CustomerProfileService service = new CustomerProfileService(mapper, new ObjectMapper());

        CustomerProfileService.ProfileSnapshot unchanged = service.updateAndLoad(
            "dingtalk", "user-conflict", "套餐是专业版");
        assertEquals("企业版", unchanged.facts().get("plan").get("value"));
        verify(mapper, never()).updateById(any(BotCustomer.class));

        CustomerProfileService.ProfileSnapshot changed = service.updateAndLoad(
            "dingtalk", "user-conflict", "现在使用专业版");
        assertEquals("专业版", changed.facts().get("plan").get("value"));
        verify(mapper).updateById(customer);
    }

    @Test
    void usesStructuredLocalModelToExtractFactsOutsideRulePhrases() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(aiModelService.chatWithExactModelJson(anyString(), anyString(), eq(7L), any())).thenReturn(
            new ChatResponse("""
                {
                  "company": {"value": "北辰科技", "explicit": true, "confidence": 0.93},
                  "role": {"value": "合同审批负责人", "explicit": true, "confidence": 0.90},
                  "product": {"value": "智签平台", "explicit": true, "confidence": 0.88},
                  "plan": {"value": "专业服务方案", "explicit": true, "confidence": 0.86},
                  "channel": {"value": "浏览器", "explicit": true, "confidence": 0.91}
                }
                """, true));
        CustomerProfileService service = intelligentService();

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            "dingtalk", "user-ai", "请记录我的工作背景和当前使用方案。我的资料已明确说明。 ");

        assertEquals("北辰科技", result.facts().get("company").get("value"));
        assertEquals("合同审批负责人", result.facts().get("role").get("value"));
        assertEquals("智签平台", result.facts().get("product").get("value"));
        assertEquals("专业服务方案", result.facts().get("plan").get("value"));
        assertEquals("网页端", result.facts().get("channel").get("value"));
        assertTrue(result.facts().get("company").get("source").equals("user_explicit_ai"));
        verify(aiModelService).chatWithExactModelJson(anyString(), anyString(), eq(7L), any());
    }

    @Test
    void acceptsSafeChineseFieldAliasesFromSmallLocalModels() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(aiModelService.chatWithExactModelJson(anyString(), anyString(), eq(7L), any())).thenReturn(
            new ChatResponse("""
                {
                  "团队名称": "云海数字服务团队",
                  "职责": "电子合同审批",
                  "熟悉平台": "智签平台",
                  "服务方案": "年度专业服务方案"
                }
                """, true));
        CustomerProfileService service = intelligentService();

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            "dingtalk", "user-ai-aliases", "请记录我的背景信息。");

        assertEquals("云海数字服务团队", result.facts().get("company").get("value"));
        assertEquals("电子合同审批", result.facts().get("role").get("value"));
        assertEquals("智签平台", result.facts().get("product").get("value"));
        assertEquals("年度专业服务方案", result.facts().get("plan").get("value"));
        assertEquals("user_explicit_ai", result.facts().get("company").get("source"));
    }

    @Test
    void acceptsCommonNamedValuesFromSmallLocalModels() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(aiModelService.chatWithExactModelJson(anyString(), anyString(), eq(7L), any())).thenReturn(
            new ChatResponse("""
                {
                  "company": {"name": "云海数字服务团队"},
                  "role": {"name": "电子合同审批"},
                  "product": {"name": "智签平台"},
                  "plan": {"name": "年度专业服务方案"},
                  "channel": {"name": "浏览器"}
                }
                """, true));
        CustomerProfileService service = intelligentService();

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            "dingtalk", "user-ai-named-values", "请记录我的工作背景和当前使用方案。");

        assertEquals("云海数字服务团队", result.facts().get("company").get("value"));
        assertEquals("电子合同审批", result.facts().get("role").get("value"));
        assertEquals("智签平台", result.facts().get("product").get("value"));
        assertEquals("年度专业服务方案", result.facts().get("plan").get("value"));
        assertEquals("网页端", result.facts().get("channel").get("value"));
        assertEquals("user_explicit_ai", result.facts().get("company").get("source"));
    }

    @Test
    void acceptsEvidenceTextInsteadOfBooleanExplicitFlag() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(aiModelService.chatWithExactModelJson(anyString(), anyString(), eq(7L), any())).thenReturn(
            new ChatResponse("""
                {
                  "company": {"value": "云海数字服务团队", "explicit": "客户明确提到他们是云海数字服务团队", "confidence": 1},
                  "channel": {"value": "浏览器", "explicit": "客户提到通过浏览器登录", "confidence": 1}
                }
                """, true));
        CustomerProfileService service = intelligentService();

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            "dingtalk", "user-ai-evidence-text", "请记录我的背景信息。");

        assertEquals("云海数字服务团队", result.facts().get("company").get("value"));
        assertEquals("网页端", result.facts().get("channel").get("value"));
        assertEquals("user_explicit_ai", result.facts().get("company").get("source"));
    }

    @Test
    void fallsBackToRulesWhenLocalModelFailsWithoutUsingAnotherModel() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(aiModelService.chatWithExactModelJson(anyString(), anyString(), eq(7L), any())).thenReturn(
            new ChatResponse("模型不可用", false));
        CustomerProfileService service = intelligentService();

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            "dingtalk", "user-ai-fallback",
            "我代表星河科技，我们使用的是点签电子合同，套餐是企业版。");

        assertEquals("星河科技", result.facts().get("company").get("value"));
        assertEquals("点签电子合同", result.facts().get("product").get("value"));
        assertEquals("企业版", result.facts().get("plan").get("value"));
        assertEquals("user_explicit", result.facts().get("company").get("source"));
    }

    @Test
    void ignoresInvalidStructuredModelOutput() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(aiModelService.chatWithExactModelJson(anyString(), anyString(), eq(7L), any())).thenReturn(
            new ChatResponse("```json {\"company\": {\"value\": \"未知\", \"explicit\": true, \"confidence\": 0.99}} ```", true));
        CustomerProfileService service = intelligentService();

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            "dingtalk", "user-ai-invalid", "我想咨询合同签署流程。");

        assertTrue(result.facts().isEmpty());
        verify(mapper, never()).insert(any(BotCustomer.class));
        verify(mapper, never()).updateById(any(BotCustomer.class));
    }

    @Test
    void doesNotCreateProfileFromAOneOffQuestion() {
        when(mapper.selectOne(any())).thenReturn(null);
        CustomerProfileService service = new CustomerProfileService(mapper, new ObjectMapper());

        CustomerProfileService.ProfileSnapshot result = service.updateAndLoad(
            "dingtalk", "user-2", "合同怎么发起签署？");

        assertFalse(result.updated());
        assertTrue(result.facts().isEmpty());
        verify(mapper, never()).insert(any(BotCustomer.class));
        verify(mapper, never()).updateById(any(BotCustomer.class));
    }

    @Test
    void injectsProfileOnlyForRelevantQuestions() {
        Map<String, Map<String, Object>> facts = new LinkedHashMap<>();
        facts.put("role", Map.of("value", "管理员"));
        CustomerProfileService.ProfileSnapshot snapshot =
            new CustomerProfileService.ProfileSnapshot(facts, false);
        CustomerProfileService service = new CustomerProfileService(mapper, new ObjectMapper());

        String relevant = service.contextFor("这个合同怎么操作？", snapshot);
        String unrelated = service.contextFor("南京明天天气怎么样？这是一个完全不同的长问题。", snapshot);
        String shortUnrelated = service.contextFor("今天天气怎么样？", snapshot);
        String contextualFollowUp = service.contextFor("这个怎么操作？", snapshot);

        assertTrue(relevant.contains("管理员"));
        assertTrue(relevant.contains("不是知识库事实"));
        assertNull(unrelated);
        assertNull(shortUnrelated);
        assertTrue(contextualFollowUp.contains("管理员"));
    }

    private CustomerProfileService intelligentService() {
        CustomerProfileService service = new CustomerProfileService(
            mapper, new ObjectMapper(), aiModelService);
        ReflectionTestUtils.setField(service, "intelligentExtractionEnabled", true);
        ReflectionTestUtils.setField(service, "intelligentExtractionModelId", 7L);
        return service;
    }
}
