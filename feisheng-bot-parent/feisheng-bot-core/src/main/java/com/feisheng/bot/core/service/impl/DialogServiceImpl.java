package com.feisheng.bot.core.service.impl;
import com.feisheng.bot.core.dto.ChatResponse;
import com.feisheng.bot.core.dto.QueryVariant;
import com.feisheng.bot.core.dto.SafetyResult;
import com.feisheng.bot.core.entity.BotAiReplyLog;
import com.feisheng.bot.core.entity.BotConversation;
import com.feisheng.bot.core.entity.BotMessage;
import com.feisheng.bot.core.mapper.BotAiReplyLogMapper;
import com.feisheng.bot.core.service.BusinessSafetyBoundaryService;
import com.feisheng.bot.core.service.ConversationServiceImpl;
import com.feisheng.bot.core.service.CustomerServicePromptProvider;
import com.feisheng.bot.core.service.EmotionService;
import com.feisheng.bot.core.service.HandoffCoordinator;
import com.feisheng.bot.core.service.IntentService;
import com.feisheng.bot.core.service.ModelAnswerSignalParser;
import com.feisheng.bot.core.service.NlpIntentClassifier;
import com.feisheng.bot.core.service.PlainTextReplyFormatter;
import com.feisheng.bot.core.service.ReplyAttachmentService;
import com.feisheng.bot.core.service.RichReplyFormatter;
import com.feisheng.bot.core.service.SensitiveDataService;
import com.feisheng.bot.core.service.TextCorrectionService;
import com.feisheng.bot.knowledge.service.KnowledgeImageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DialogServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(DialogServiceImpl.class);
    private static final int CHARS_PER_TOKEN = 2;
    private static final double INTENT_REWRITE_RETRIEVAL_WEIGHT = 0.85;
    private static final double CONTEXT_RESOLUTION_RETRIEVAL_WEIGHT = 0.92;
    private static final double CONTEXT_FOLLOW_UP_RETRIEVAL_WEIGHT = 0.70;
    private static final String NO_ANSWER_SIGNAL = "__NO_ANSWER__";
    private static final String PARTIAL_ANSWER_SIGNAL = "__ANSWER_PARTIAL__";
    private static final ModelAnswerSignalParser MODEL_ANSWER_SIGNAL_PARSER =
        new ModelAnswerSignalParser();
    private static final TextCorrectionService TEXT_CORRECTION = new TextCorrectionService();
    private static final Pattern STRUCTURED_ANSWER_IN_CONTEXT = Pattern.compile(
        "(?ms)^答案：(.*?)(?=^事实：|^回答时先锁定|\\z)");
    private static final Pattern ANNUAL_SIGNING_VOLUME_NUMBER = Pattern.compile("\\d{1,9}");
    private static final String PLAIN_TEXT_OUTPUT_INSTRUCTION =
        "只输出干净的普通文本。禁止使用 Markdown 或 HTML 格式，禁止输出 **、*、__、#、```、> 等格式标记；"
            + "禁止使用 ✅、⚠️、🔍、• 等装饰性图标；需要分点时优先使用数字序号，"
            + "只有非步骤的简短要点才使用普通短横线，不要给标题或关键词添加加粗符号。";
    private static final String ADAPTIVE_REPLY_STRUCTURE_INSTRUCTION =
        "按问题复杂度选择最小充分结构，不得机械套用所有栏目。"
            + "简单事实或解释类问题直接用一至三句话回答，不添加标题；"
            + "操作、流程或排查类问题先用一句话直接回答，确有两个以上步骤时再输出“操作步骤：”，"
            + "并使用“1. ”、“2. ”这样的数字序号；"
            + "列举多个类型、模式或方案时，首句说明总数，再用数字序号逐项说明。"
            + "只有当前可核实事实明确提供了与问题直接相关的前提、限制、风险或例外时，才输出“注意：”；"
            + "需要引导客户继续操作或补充信息时，直接使用自然的客服用语，不得输出“下一步：”标签。"
            + "缺少依据的栏目必须省略，不得为凑结构而编造，不得输出空栏目，"
            + "不得输出 Q:、A:、“直接答案：”或装饰性图标。";
    private static final String COMPANY_INTRODUCTION_QUERY = "你们主要是做什么的？";
    private static final String CONTRACT_FILE_LAUNCH_QUERY = "发起合同有几种方式？";
    private static final String PAPER_CONTRACT_ARCHIVE_QUERY =
        "怎么把之前的纸质合同上传到点签里？";
    private static final String EXPIRED_ACCOUNT_LOGIN_QUERY =
        "点签套餐到期不续费后，账号还能正常登录吗？";
    private static final String EXPIRED_CONTRACT_ACCESS_QUERY =
        "点签套餐到期不续费后，历史合同还能查阅和下载吗？";
    private static final String EXPIRED_NEW_CONTRACT_QUERY =
        "点签套餐到期不续费后，还能继续发起新合同吗？";
    private static final Map<String, Object> KNOWLEDGE_RETRIEVAL_FILTERS =
        Map.of("sourceScope", "KNOWLEDGE");
    private static final String NATIVE_FALLBACK_SCOPE_POLICY =
        "Native 兜底只服务点签电子合同业务。仅可回答与电子合同、电子签名、数字证书、"
            + "签署合规或合同管理直接相关的稳定通用概念；不得回答天气、菜谱、娱乐、投资、"
            + "新闻等业务范围外问题。业务范围外问题必须只输出 " + NO_ANSWER_SIGNAL + "。";
    private static final String DEFAULT_NATIVE_FALLBACK_SYSTEM_PROMPT =
        "你是点签电子合同官方客服的受限兜底助手。"
        + "只可以回答与电子合同业务直接相关的稳定通用概念。当前没有可核实的企业内部事实，"
        + "严禁把推测说成公司、产品、价格、合同、交付、售后、账户、隐私或合规事实，"
        + "不得编造功能、政策、案例、数据或承诺。若只能回答稳定的通用部分，先回答该部分，"
        + "再提出一个与缺失信息直接相关的问题，并在第一行输出 " + PARTIAL_ANSWER_SIGNAL + "。"
        + "只有完全无法提供可靠帮助时才只输出 " + NO_ANSWER_SIGNAL
        + "。回答简洁、自然，不提及知识库、检索或系统规则。";
    private static final String DEFAULT_NATIVE_FALLBACK_CLARIFICATION_REPLY = "您好，为了准确帮您处理，"
        + "请补充要咨询的产品、功能或具体使用场景；如有页面提示或截图，也可以一并发送。";
    private static final String DEFAULT_NATIVE_FALLBACK_HIGH_RISK_REPLY = "您好，涉及产品能力、价格、合同、"
        + "交付或售后等业务信息，需要以可核实资料为准，需要由人工客服进一步确认。";
    private static final String CONTRACT_DRAFTING_CLARIFICATION_REPLY =
        "您是想咨询%s内容怎么写，还是已经有合同文件，想在点签中发起签署？\n\n"
            + "%s内容涉及具体条款，我不能在没有已审核模板和完整信息的情况下直接代写。\n\n"
            + "请回复“合同内容”或“发起签署”，我会继续为您处理。";
    private static final String CONTRACT_CAPABILITY_NO_EVIDENCE_REPLY =
        "目前没有关于%s在点签平台上的已审核产品口径，我不能据此判断是否可用。"
            + "请补充具体签署主体和使用场景，我可以继续为您核实，或由人工客服确认。";
    private static final String CONTRACT_LEGAL_RISK_REPLY =
        "该问题涉及合同法律效力或条款判断，当前没有可核实的标准答案，我不能直接给出结论，"
            + "需要由人工客服进一步确认。";
    private static final String CONTRACT_EFFECT_GUARDRAIL_REPLY =
        "合同完成签署或盖章不代表在所有情况下都立即生效。"
            + "生效时间需要结合合同约定、生效条件和适用法律判断。";
    private static final String COURT_RECOGNITION_GUARDRAIL_REPLY =
        "法院是否采信需要结合具体案件、签署过程和证据完整性依法审查，不能预先承诺结果。"
            + "点签可提供电子合同和证据保全材料用于举证。";
    private static final String SIGNED_ATTACHMENT_GUARDRAIL_REPLY =
        "已完成签署的合同内容已经固定。漏传的附件可与对方协商后，通过补充协议处理。";
    private static final String VERIFICATION_CODE_GUARDRAIL_REPLY =
        "验证码收不到时，请按以下顺序处理：\n\n"
            + "1. 先检查手机信号是否正常，并避免连续重复获取验证码。\n"
            + "2. 如果短时间内频繁获取验证码，可能触发运营商短信接收限制；"
            + "这种限制需等待24小时后由运营商自动解除。\n"
            + "3. 若急需签署，可先在点签账户内设置签约密码，使用密码完成签署，"
            + "后续或稍晚再使用短信验证码。\n\n"
            + "若并非频繁获取导致，或等待24小时后仍未恢复，请联系人工客服进一步核查。";
    private static final String COMPETITOR_COMPARISON_GUARDRAIL_REPLY =
        "不同电子合同平台的安全性和法律效力不能脱离具体需求直接下结论。"
            + "选型时可比较实名认证与签署意愿验证、数字证书、存证与司法举证能力、"
            + "合规资质、系统集成及服务保障；对其他平台缺少可核实资料时，不作优劣判断。";
    private static final String SERVICE_RESPONSE_GUARDRAIL_REPLY =
        "飞晟科技针对本地企业提供远程问题1小时内响应，并可在预约后12小时内上门协助。"
            + "这里的时效是响应和协助安排，不等于问题已解决，也不构成对全部问题解决时间的承诺。";
    private static final String MARKET_SHARE_EVIDENCE_GUARDRAIL_REPLY =
        "关于全国招投标市场占有率80%以上的说法，需要核实官方统计口径、发布主体和原始证明材料。"
            + "缺少这些可验证材料时，不能将该比例表述为已获官方认证。";
    private static final String DEFAULT_PRICE_HANDOFF_REPLY = "您咨询的是价格相关信息。价格、报价和优惠需要由客服"
        + "根据实际情况确认，已为您提交人工核实。您可以继续补充套餐类型或使用人数，我会一并同步。";
    private static final String DEFAULT_PRICE_HANDOFF_FAILED_REPLY = "价格、报价和优惠需要由客服确认，但当前未能成功提交"
        + "人工请求。请稍后重试或联系人工客服。";
    private static final String PRICE_VOLUME_QUESTION =
        "请问贵公司一年的签署量大约在多少呢？";
    private static final String STANDARD_PACKAGE_PRICE_REPLY =
        "目前我们的价格以官网标准套餐价格为准，可按需求选择相应的合同套餐份数进行购买。";
    private static final String ENTERPRISE_PRICE_REPLY =
        "这边给您安排客户经理对接，根据您的合同签署量、使用年限及功能需求，"
            + "定制性价比更高的服务方案。需要我帮您转人工吗？";
    private static final String PRICE_HANDOFF_DECLINED_REPLY =
        "好的，您可以先参考上述方案；后续需要客户经理对接时，随时告诉我“转人工”即可。";
    private static final String EVIDENCE_CONFLICT_REPLY =
        "目前可确认的价格口径存在冲突，我不能直接给出具体金额，需要由人工客服进一步核实。";
    private static final String UNIFIED_CONTRACT_PRICING_REPLY =
        "您好！感谢您的咨询。关于合同套餐和每份合同的价格，我们点签电子合同平台是以套餐形式进行收费的，"
            + "您可以根据企业的实际签署量选择相应的套餐份数进行购买。\n\n"
            + "目前，官网的标准套餐价格会根据不同的份数档位有所差异。为了给您提供最准确的价格信息，"
            + "建议您访问我们的官网查看具体的套餐详情和价格：https://www.fs-signature.com/。\n\n"
            + "也可以直接拨打客服热线询问（186 8963 3999）。";
    private static final String OFFICIAL_WEBSITE_REPLY =
        "您好！我们公司的官网地址是：https://www.fs-signature.com/。";
    private static final String CA_PRODUCT_CLARIFICATION_REPLY =
        "您是咨询翔晟CA吗还是点签电子合同平台呢？";
    private static final String UKEY_PRODUCT_CLARIFICATION_REPLY =
        "您是咨询翔晟UKey吗？还是点签电子合同平台呢？";
    private static final String UKEY_DIANQIAN_LIMIT_REPLY =
        "您好，这个问题我不能准确地回答，需要我帮您转接人工吗？";
    private static final String INSUFFICIENT_LIST_EVIDENCE_REPLY =
        "目前可确认的信息不足以列出完整、准确的材料清单，我不能补充未经核实的材料，需要由人工客服进一步确认。";
    private static final String PRODUCT_USAGE_CHANNEL_QUERY = "点签可以在哪里使用？";
    private static final String PRODUCT_USAGE_CLARIFICATION_REPLY =
        "你具体想进行发起合同、签署合同还是企业认证？请告诉我使用端，我再说明对应步骤。";
    private static final String PROCEDURE_EVIDENCE_CLARIFICATION_REPLY =
        "目前可确认的信息不足以给出完整操作步骤。请补充要完成的具体操作和使用端，我再按已核实信息说明。";
    private static final String STANDALONE_APP_BOUNDARY_REPLY =
        "点签目前不提供独立手机 APP。手机用户可以通过微信公众号、微信小程序或短信签署链接办理相关操作；"
            + "电脑用户可以使用 PC 网页版。";
    private static final String DEFAULT_MANUAL_HANDOFF_REPLY =
        "已为您提交人工客服请求，请稍候，客服会尽快接入。";
    private static final String DEFAULT_MANUAL_HANDOFF_FAILED_REPLY =
        "当前未能成功提交人工客服请求，请稍后重试或联系人工客服。";
    private static final String BASIC_IDENTITY_REPLY =
        "我是点签电子合同官方智能客服，可以为您解答点签产品和使用相关问题。";
    private static final String BASIC_CAPABILITY_REPLY =
        "我可以为您解答点签电子合同的产品功能、使用操作、合同发起与签署、企业认证、"
            + "电子印章和合同管理等问题。涉及价格优惠、个案法律判断或需要核验身份与账户数据的操作，"
            + "我会交由人工客服进一步确认。";
    private static final String BASIC_GREETING_REPLY =
        "您好，我是点签电子合同官方智能客服。请问您想咨询产品功能、使用操作，还是合同签署相关问题？";
    private static final String BASIC_THANKS_REPLY = "不客气，很高兴能帮到您。";
    private static final String BASIC_GOODBYE_REPLY =
        "好的，再见。需要咨询点签电子合同时，欢迎随时联系。";
    private static final String EXPIRED_ACCOUNT_POLICY_REPLY =
        "不续费后，您的点签账号仍可正常登录，之前的数据和已签合同会继续保留，"
            + "可以随时查阅、下载，但不能进行合同的发起；如需签署新合同，需要续费购买。";
    private static final String AUTOMATIC_SIGNING_REMINDER_REPLY =
        "可以。合同发起后，系统可通过短信、微信和钉钉消息自动提醒对方签约。";
    private static final String MANUAL_SIGNING_REMINDER_REPLY =
        "您也可以手动把签约链接或二维码发给对方。";
    private static final Set<String> BASIC_IDENTITY_QUESTIONS = Set.of(
        "你是谁", "请问你是谁", "你是什么", "你是什么人", "你叫什么", "你叫什么名字",
        "你的身份是什么", "介绍一下你自己", "自我介绍一下", "你是机器人吗", "你是ai吗",
        "你是人工智能吗", "你是客服吗");
    private static final Set<String> BASIC_CAPABILITY_QUESTIONS = Set.of(
        "你能做什么", "你能帮我做什么", "你可以做什么", "你会做什么", "你能干什么",
        "你能回答什么", "你能回答哪些问题", "你可以回答什么问题", "你能提供什么帮助",
        "你可以提供什么帮助", "你能帮我什么", "你可以帮我什么", "你能帮忙吗");
    private static final Set<String> BASIC_GREETINGS = Set.of(
        "你好", "您好", "hello", "hi", "嗨", "哈喽", "在吗", "在不在", "有人在吗", "客服在吗");
    private static final Set<String> BASIC_THANKS = Set.of(
        "谢谢", "谢谢你", "感谢", "感谢你", "多谢", "辛苦了", "好的谢谢", "非常感谢",
        "谢谢帮助", "已经解决了谢谢");
    private static final Set<String> BASIC_GOODBYES = Set.of(
        "再见", "拜拜", "bye", "goodbye", "先这样", "下次再聊", "没事了", "不用了谢谢");
    private static final String DETAILED_LIST_ANSWER_INSTRUCTION = "当前问题要求列举多个模式、类型或方案。"
        + "请在首句明确总数，再使用数字序号对每一项分别说明：名称、核心作用或特点、适用场景；"
        + "只使用内部事实中明确提供的内容，不要只返回名称，也不要补充未被事实支持的细节。";
    private static final String OPERATIONAL_ANSWER_INSTRUCTION = "当前问题是在询问操作或流程。"
        + "请先直接说明如何完成，并覆盖内部事实中与当前主体直接相关的已确认前置条件、操作动作和必要结果；"
        + "不得只列出支持渠道或泛泛介绍功能。确有两个以上独立操作动作时，输出“操作步骤：”并使用数字序号；"
        + "对于未明确使用端的宽泛使用问题，如果内部事实表明不同渠道的流程不同，先概括可用渠道，"
        + "再使用自然问句确认用户具体使用端，不得使用“下一步：”标签，也不得把不同渠道混写成一个操作流程。"
        + "如果只能确认部分步骤，先回答已确认内容，再自然询问缺失的使用端或具体场景，"
        + "不得编造入口、按钮或页面名称。";
    private static final String CONTRACT_LAUNCH_METHOD_INSTRUCTION = "客户询问的是合同发起方式，"
        + "它与产品服务模式不是同一概念。根据当前业务口径，发起合同主要有两种方式："
        + "上传文件发起和模板发起。平台通用模板属于模板发起，不单独计算为第三种；"
        + "SaaS、OpenAPI、定制化开发属于服务模式，不得混入合同发起方式。"
        + "请分别说明两种发起方式的操作要点，并说明合同完成盖章后的生效和归档结果。";
    private static final String CONTRACT_UPLOAD_AMBIGUITY_INSTRUCTION =
        "客户所说的“已有合同”或“上传合同”可能有两种场景："
            + "一是合同内容已经写好但尚未签署，需要上传文件发起电子签署；"
            + "二是合同已经在线下签署完成，需要上传纸质合同扫描件归档。"
            + "必须依据企业内部事实区分这两个场景，不得默认客户指其中一个。"
            + "除非内部事实明确说明不支持，否则不得把未检索到的信息推断为平台不支持。"
            + "内部事实能支持两者时，使用数字序号分别简要说明，再自然询问客户选择“发起签署”或“纸质归档”，不得使用“下一步：”标签；"
            + "只能确认一个场景时，先回答已确认内容，再询问合同是否已经签署。";
    private static final String CONTRACT_TYPE_ALREADY_SPECIFIED_INSTRUCTION =
        "客户已经明确给出了合同类型“%s”，当前不缺合同类型。"
            + "不得再追问商品房、二手房、具体合同类型或签署主体。"
            + "如果本轮企业内部事实不能确认点签是否支持该合同类型，只输出 "
            + NO_ANSWER_SIGNAL + "，不得用新的合同类型问题替代答案。";
    private static final String COMPANY_INTRODUCTION_ANSWER_INSTRUCTION =
        "客户当前是在请求公司介绍，不是在询问未经证实的口碑、排名或经营评价。"
            + "请仅依据企业内部事实，客观概括公司定位、主营业务以及已明确的产品或服务；"
            + "已有这些事实时必须直接作答，不得输出 " + NO_ANSWER_SIGNAL + "。";
    private static final String EVIDENCE_CONFLICT_INSTRUCTION =
        "如果企业内部事实在文件格式、适用范围、数量或条件上存在冲突，"
            + "优先采用表述更具体且限制更严格的规则，不得同时输出相互矛盾的说法；"
            + "仍无法判断时省略冲突细节，并说明需要进一步核实。";
    private static final String COMPOUND_ANSWER_INSTRUCTION =
        "客户一句话包含多个并列问题。请逐项核对企业内部事实并完整作答，不得只回答其中一项，"
            + "也不得因为一项缺少依据而拒答全部问题。若只能回答部分事项，必须使用 "
            + PARTIAL_ANSWER_SIGNAL + "，明确已确认部分，并只追问一个与未确认事项直接相关的问题。";
    private static final String EVIDENCE_BACKED_ANSWER_RETRY_INSTRUCTION =
        "当前已提供与客户问题相关的企业内部事实。请重新逐条核对并回答事实能够确认的内容，"
            + "不得补充事实之外的产品能力、规则或承诺；只有这些事实完全不能回答核心问题时，"
            + "才输出 " + NO_ANSWER_SIGNAL + "。";
    private static final String POLARITY_CONSISTENCY_INSTRUCTION =
        "输出前逐项核对支持与不支持、可以与不可以、自动与手动、保留与删除、收费与免费等相反结论；"
            + "不得颠倒企业内部事实，不得把同时存在的多种方式缩减成唯一方式。";
    private static final String SERVICE_LEVEL_PROMISE_INSTRUCTION =
        "客户正在核实服务响应或问题解决时效。必须严格区分“响应”和“解决”："
            + "只能复述企业内部事实明确提供的服务对象、适用范围和时间口径，"
            + "不得把局部场景的响应时效扩大为所有问题的解决承诺；"
            + "如果内部事实只说明响应时效，应明确该时效不等于问题已经解决。";
    private static final List<String> PRICE_TERMS = List.of(
        "价格", "报价", "价格表", "价目表", "多少钱", "费用", "收费", "资费",
        "套餐价", "套餐价格", "折扣", "优惠");
    private static final List<String> CUSTOM_QUOTE_CONTEXT_TERMS = List.of(
        "定制", "专属", "商务", "最终", "折扣", "优惠", "议价", "最低价", "底价",
        "便宜", "打折", "合同价", "采购价", "预算", "按人数", "按人", "签署量", "年用量");
    private static final List<String> SPECIFIC_PRICE_CONTEXT_TERMS = List.of(
        "会员", "会员价", "会员价格", "专业版", "高级版", "通用版", "基础版",
        "个人版", "企业版", "活动价", "促销价", "续费价", "升级价");
    private static final List<String> BUSINESS_SCOPE_TERMS = List.of(
        "点签", "飞晟", "电子合同", "合同", "签署", "签约", "签名", "签章", "印章", "用印",
        "骑缝章", "认证", "实名", "账号", "账户", "登录", "模板", "附件", "发起", "撤回",
        "审批", "归档", "存证", "公证", "仲裁", "法律效力", "证书", "发票", "套餐", "报价",
        "客服", "热线", "售后", "小程序", "钉钉", "微信", "上传", "下载", "验证码", "管理员",
        "员工", "接收方", "发起方", "签署方", "api", "pc");
    private static final List<String> NATIVE_FALLBACK_DOMAIN_ANCHORS = List.of(
        "点签", "飞晟", "电子合同", "电子签约", "电子签名", "数字签名", "电子签章",
        "数字证书", "合同", "签署", "签约", "签章", "盖章", "用印", "印章", "骑缝章",
        "存证", "公证", "仲裁", "法律效力", "实名认证", "企业认证", "合同模板", "合同附件");
    private static final Pattern NATIVE_FALLBACK_UNRELATED_REQUEST = Pattern.compile(
        "(?is).*(?:(?:写|作|创作|生成).{0,16}(?:诗|歌词|小说|故事|作文)|"
            + "(?:做|煮|炒|炖|烤).{0,16}(?:饭|菜|汤|火锅|鸡|肉|面)|"
            + "(?:饭|菜|汤|火锅|鸡|肉|面).{0,16}(?:怎么|如何)(?:做|煮|炒|炖|烤)|"
            + "(?:登录|注册).{0,8}游戏|(?:下载|播放|唱).{0,8}(?:歌曲|音乐)|"
            + "(?:推荐|预测|分析).{0,8}(?:股票|彩票|星座|运势|电影|足球|篮球)).*");
    private static final List<String> CLEARLY_UNRELATED_TERMS = List.of(
        "现在几点", "现在是几点", "现在多少点", "现在是多少点", "当前时间", "现在时间", "几点了",
        "今天几号", "星期几", "周几",
        "天气", "天气预报", "下雨", "下雪", "气温", "降雨", "刮风", "台风路径",
        "讲个笑话", "写首诗", "菜谱", "怎么做菜", "股票", "彩票", "星座", "运势", "足球",
        "篮球", "电影", "歌曲", "新闻", "热搜");
    @Value("${rag.no-answer.reply}")
    private String noAnswerReply;

    @Value("${rag.out-of-scope.reply}")
    private String outOfScopeReply;

    @Value("${rag.unrelated.reply:这个问题不属于点签电子合同业务服务范围。您可以继续咨询点签产品、合同签署或使用操作相关问题。}")
    private String unrelatedReply;

    @Value("${rag.error.reply:当前查询出现异常，暂时无法完成回答，请稍后重试。}")
    private String errorReply;

    @Value("${rag.out-of-scope.keywords}")
    private String outOfScopeKeywords;

    @Value("${rag.no-answer.transfer:false}")
    private boolean transferOnNoAnswer;

    @Value("${rag.handoff.low-confidence-threshold:0.55}")
    private double lowConfidenceThreshold;

    @Value("${rag.context.max-history-messages:6}")
    private int maxHistoryMessages;

    @Value("${rag.context.max-retrieval-history-messages:4}")
    private int maxRetrievalHistoryMessages;

    @Value("${rag.context.max-retrieval-history-chars:1200}")
    private int maxRetrievalHistoryChars;

    @Value("${rag.context.max-prompt-tokens:4000}")
    private int maxPromptTokens;

    @Value("${rag.native-fallback.enabled:false}")
    private boolean nativeFallbackEnabled;

    @Value("${rag.native-fallback.high-risk-keywords:合同条款,法律效力,违约责任,赔偿责任,合规承诺,保证结果,隐私泄露,个人信息泄露}")
    private String nativeFallbackHighRiskKeywords;

    @Value("${rag.native-fallback.clarification-keywords:这个,那个,它,这款,那款,然后呢}")
    private String nativeFallbackClarificationKeywords;

    @Value("${rag.native-fallback.clarification-reply:您好，为了准确帮您处理，请补充要咨询的产品、功能或具体使用场景；如有页面提示或截图，也可以一并发送。}")
    private String nativeFallbackClarificationReply;

    @Value("${rag.native-fallback.high-risk-reply:您好，涉及产品能力、价格、合同、交付或售后等业务信息，需要以可核实资料为准，已为您转接人工客服确认。}")
    private String nativeFallbackHighRiskReply;

    @Value("${rag.native-fallback.high-risk-transfer:true}")
    private boolean nativeFallbackHighRiskTransfer;

    @Value("${rag.handoff.price-keywords:定制报价,专属报价,商务报价,最终报价,折扣,优惠,议价,最低价,底价,便宜点,打折}")
    private String priceHandoffKeywords;

    @Value("${rag.handoff.price-reply:您咨询的是价格相关信息。价格、报价和优惠需要由客服根据实际情况确认，已为您提交人工核实。您可以继续补充套餐类型或使用人数，我会一并同步。}")
    private String priceHandoffReply;

    @Value("${rag.handoff.price-failed-reply:价格、报价和优惠需要由客服确认，但当前未能成功提交人工请求。请稍后重试或联系人工客服。}")
    private String priceHandoffFailedReply;

    @Value("${ai.customer-service.native-fallback-system-prompt:}")
    private String nativeFallbackSystemPrompt;

    private final ConversationServiceImpl conversationService;
    private final MessageServiceImpl messageService;
    private final AiModelServiceImpl aiModelService;
    private final SafetyServiceImpl safetyService;
    private final BusinessSafetyBoundaryService businessSafetyBoundaryService;
    private final CustomerServicePromptProvider promptProvider;
    private final BotAiReplyLogMapper aiReplyLogMapper;
    private final RagRetrievalService retrievalService;
    private final UnmatchedQuestionService unmatchedQuestionService;
    private final BusinessToolOrchestrator businessToolOrchestrator;
    private final IntentService intentService;
    private final NlpIntentClassifier nlpIntentClassifier;
    private final SensitiveDataService sensitiveDataService;
    private final HandoffCoordinator handoffCoordinator;
    private final EmotionService emotionService;
    private final ReplyAttachmentService replyAttachmentService;
    private final ContextualQueryResolver contextualQueryResolver;
    private final ObjectMapper objectMapper;

    public DialogServiceImpl(ConversationServiceImpl conversationService,
                             MessageServiceImpl messageService,
                             AiModelServiceImpl aiModelService,
                             SafetyServiceImpl safetyService,
                             BusinessSafetyBoundaryService businessSafetyBoundaryService,
                             CustomerServicePromptProvider promptProvider,
                             BotAiReplyLogMapper aiReplyLogMapper,
                             RagRetrievalService retrievalService,
                             UnmatchedQuestionService unmatchedQuestionService,
                             BusinessToolOrchestrator businessToolOrchestrator,
                             IntentService intentService,
                             NlpIntentClassifier nlpIntentClassifier,
                             SensitiveDataService sensitiveDataService,
                             HandoffCoordinator handoffCoordinator,
                             EmotionService emotionService,
                             ReplyAttachmentService replyAttachmentService,
                             ContextualQueryResolver contextualQueryResolver,
                             ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.aiModelService = aiModelService;
        this.safetyService = safetyService;
        this.businessSafetyBoundaryService = businessSafetyBoundaryService;
        this.promptProvider = promptProvider;
        this.aiReplyLogMapper = aiReplyLogMapper;
        this.retrievalService = retrievalService;
        this.unmatchedQuestionService = unmatchedQuestionService;
        this.businessToolOrchestrator = businessToolOrchestrator;
        this.intentService = intentService;
        this.nlpIntentClassifier = nlpIntentClassifier;
        this.sensitiveDataService = sensitiveDataService;
        this.handoffCoordinator = handoffCoordinator;
        this.emotionService = emotionService;
        this.replyAttachmentService = replyAttachmentService;
        this.contextualQueryResolver = contextualQueryResolver;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> send(String channelType, String channelUserId, String text, String title) {
        return send(channelType, channelUserId, text, title, null, null);
    }

    public Map<String, Object> send(String channelType, String channelUserId, String text, String title,
                                    String providedRagContext) {
        return send(channelType, channelUserId, text, title, providedRagContext, null);
    }

    public Map<String, Object> send(String channelType, String channelUserId, String text, String title,
                                    String providedRagContext, Long preferredModelId) {
        return send(channelType, channelUserId, text, title, providedRagContext,
            preferredModelId, null);
    }

    public Map<String, Object> send(String channelType, String channelUserId, String text, String title,
                                    String providedRagContext, Long preferredModelId,
                                    String requestedPromptVersion) {
        String promptVersion = promptProvider.resolveVersion(requestedPromptVersion);
        return withPromptVersion(sendWithContext(channelType, channelUserId, text, title,
            providedRagContext, null, preferredModelId, promptVersion), promptVersion);
    }

    public Map<String, Object> sendWithContext(String channelType, String channelUserId,
                                               String text, String title,
                                               String providedRagContext,
                                               List<Map<String, Object>> providedCitations,
                                               Long preferredModelId) {
        String promptVersion = promptProvider.resolveVersion(null);
        return withPromptVersion(sendWithContext(channelType, channelUserId, text, title,
            providedRagContext, providedCitations, preferredModelId, promptVersion), promptVersion);
    }

    private Map<String, Object> sendWithContext(String channelType, String channelUserId,
                                                String text, String title,
                                                String providedRagContext,
                                                List<Map<String, Object>> providedCitations,
                                                Long preferredModelId,
                                                String promptVersion) {
        return sendInternal(channelType, channelUserId, text, title,
            providedRagContext, providedCitations, null, false, preferredModelId, promptVersion);
    }

    public Map<String, Object> sendWithMultimodalContext(
            String channelType, String channelUserId, String text, String title,
            String providedRagContext, List<Map<String, Object>> providedCitations,
            String modalityContext, Long preferredModelId) {
        String promptVersion = promptProvider.resolveVersion(null);
        return withPromptVersion(sendInternal(channelType, channelUserId, text, title,
            providedRagContext, providedCitations, modalityContext, true, preferredModelId,
            promptVersion), promptVersion);
    }

    private Map<String, Object> sendInternal(
            String channelType, String channelUserId, String text, String title,
            String providedRagContext, List<Map<String, Object>> providedCitations,
            String modalityContext, boolean mergeGlobalRetrieval, Long preferredModelId,
            String requestedPromptVersion) {
        long started = System.currentTimeMillis();
        long retrievalLatencyMs = 0L;
        long modelLatencyMs = 0L;
        String promptVersion = promptProvider.resolveVersion(requestedPromptVersion);
        Set<String> redactedTypes = new LinkedHashSet<>();
        String safeText = redact(text, redactedTypes);
        String understandingText = TEXT_CORRECTION.correct(safeText);
        String safeTitle = redact(title, redactedTypes);
        String safeProvidedContext = redact(providedRagContext, redactedTypes);
        String safeModalityContext = redact(modalityContext, redactedTypes);
        List<Map<String, Object>> safeProvidedCitations = redactMaps(
            providedCitations, redactedTypes);

        BotConversation conversation = conversationService.getOrCreate(
            channelType, channelUserId, safeTitle);
        BotMessage userMessage = saveMessage(conversation.getId(), "user", safeText, null);
        List<BotMessage> recentMessages = messageService.getByConversation(conversation.getId());
        EmotionService.EmotionResult emotion = emotionService.analyze(
            safeText, recentMessages, userMessage.getId());
        messageService.updateMetadata(userMessage,
            toJson(Map.of("emotion", emotionDetails(emotion))));
        updateConversationEmotion(conversation, emotion);

        boolean waitingForHuman = isWaitingForHuman(conversation);
        if (isHumanHandling(conversation)) {
            return humanHandlingResponse(conversation, safeText, emotion, started, redactedTypes);
        }
        if (waitingForHuman) {
            handoffCoordinator.recordUserMessage(conversation.getId(), safeText);
            Map<String, Object> waitingResponse = waitingHandoffResponse(
                conversation, safeText, recentMessages, emotion, started, redactedTypes);
            if (waitingResponse != null) return waitingResponse;
        }

        SafetyResult preCheck = safetyService.checkUserInput(safeText);
        if (preCheck.isBlocked()) {
            return blockedResponse(conversation, preCheck, emotion, started, redactedTypes);
        }

        SafetyResult businessPreCheck = businessSafetyBoundaryService.check(safeText);
        if (businessPreCheck.isBlocked()) {
            return blockedResponse(
                conversation, businessPreCheck, emotion, started, redactedTypes);
        }

        if (isPreviousQuestionRequest(understandingText)) {
            return previousQuestionResponse(
                conversation, preCheck, recentMessages, safeText, emotion, started,
                redactedTypes, promptVersion);
        }

        if (isPriceHandoffConsent(understandingText, recentMessages)) {
            return manualHandoffResponse(conversation, emotion, started, redactedTypes);
        }
        if (isPriceHandoffDeclined(understandingText, recentMessages)) {
            return priceQualificationResponse(
                conversation, preCheck, PRICE_HANDOFF_DECLINED_REPLY,
                "answered", AnswerDecision.ANSWER, "price_handoff_declined",
                emotion, started, redactedTypes);
        }
        if (isAwaitingAnnualSigningVolume(recentMessages, understandingText)) {
            return annualSigningVolumeResponse(
                conversation, preCheck, classifyAnnualSigningVolume(understandingText, true),
                emotion, started, redactedTypes);
        }

        BasicConversationIntent basicIntent = matchBasicConversationIntent(understandingText);
        if (basicIntent != null) {
            return basicConversationResponse(
                conversation, preCheck, basicIntent, emotion, started, redactedTypes,
                promptVersion);
        }

        // An explicit human request is a deterministic routing command, not a
        // knowledge question. Handle it before pricing, tools, and RAG so a
        // phrase such as "转人工" cannot fall through to the no-answer reply.
        if (isExplicitHandoffRequest(understandingText)) {
            return manualHandoffResponse(conversation, emotion, started, redactedTypes);
        }

        if (isUKeyDianqianQuestion(understandingText)) {
            return basicConversationResponse(
                conversation, preCheck, BasicConversationIntent.UKEY_DIANQIAN_LIMIT,
                emotion, started, redactedTypes, promptVersion);
        }
        if (isOtherProductClarificationQuestion(understandingText)) {
            BasicConversationIntent clarificationIntent = hasStandaloneCa(understandingText)
                ? BasicConversationIntent.CA_PRODUCT_CLARIFICATION
                : BasicConversationIntent.UKEY_PRODUCT_CLARIFICATION;
            return basicConversationResponse(
                conversation, preCheck, clarificationIntent,
                emotion, started, redactedTypes, promptVersion);
        }

        if (isOfficialWebsiteQuestion(understandingText, recentMessages)) {
            return basicConversationResponse(
                conversation, preCheck, BasicConversationIntent.OFFICIAL_WEBSITE,
                emotion, started, redactedTypes, promptVersion);
        }

        if (isUnifiedContractPricingQuestion(understandingText, recentMessages)) {
            return priceQualificationResponse(
                conversation, preCheck, UNIFIED_CONTRACT_PRICING_REPLY,
                "answered", AnswerDecision.ANSWER, "unified_contract_pricing",
                emotion, started, redactedTypes);
        }

        if (isPriceQualificationQuestion(understandingText)) {
            return annualSigningVolumeResponse(
                conversation, preCheck, classifyAnnualSigningVolume(understandingText, false),
                emotion, started, redactedTypes);
        }

        // Price information is an explicit human-confirmation boundary. This
        // executes before tools, retrieval, and the model, even if a price table
        // has been uploaded to the knowledge base.
        if (isPriceQuestion(understandingText)) {
            return priceHandoffResponse(conversation, safeText, emotion, started, redactedTypes);
        }

        BusinessToolOrchestrator.ToolRoutingResult toolRouting = businessToolOrchestrator.route(
            conversation.getId(), channelType, channelUserId, understandingText, recentMessages);
        if (toolRouting != null && toolRouting.handled()) {
            return toolResponse(
                conversation, preCheck, toolRouting, emotion, started, redactedTypes);
        }

        // Resolve the conversational topic before applying configurable intent
        // replies. A short follow-up such as "企业的呢？" or "怎么登录？"
        // must be answered against its inherited topic instead of being
        // intercepted by a broad keyword rule (for example "登录" or "合同").
        ContextualQueryResolver.Resolution queryResolution =
            contextualQueryResolver.resolve(recentMessages, understandingText);
        boolean contextualQuestion = queryResolution.contextDependent()
            || queryResolution.rewritten();
        IntentService.IntentMatch intentMatch = contextualQuestion
            ? null : intentService.match(understandingText).orElse(null);
        if (intentMatch != null) {
            return intentResponse(
                conversation, preCheck, intentMatch, emotion, started, redactedTypes);
        }

        String resolvedRetrievalQuestion = stripLeadingCourtesyPrefix(queryResolution.query());
        NlpIntentClassifier.IntentAnalysis nlpIntent =
            nlpIntentClassifier.classify(resolvedRetrievalQuestion);
        boolean companyIntroductionQuestion = isCompanyIntroductionQuestion(understandingText);
        boolean compoundRenewalQuestion = isCompoundRenewalQuestion(understandingText);
        boolean broadProductUsageQuestion = nlpIntent.intentCode()
            == NlpIntentClassifier.IntentCode.PRODUCT_USAGE
            && isBroadProductUsageQuestion(resolvedRetrievalQuestion);
        String retrievalQuery = companyIntroductionQuestion
            ? COMPANY_INTRODUCTION_QUERY
            : broadProductUsageQuestion ? PRODUCT_USAGE_CHANNEL_QUERY
            : nlpIntent.retrievalQuery();
        String primaryRetrievalQuery = broadProductUsageQuestion
            ? retrievalQuery : queryResolution.previousQuestionMerged()
            ? resolvedRetrievalQuestion
            : queryResolution.rewritten() ? retrievalQuery : resolvedRetrievalQuestion;
        List<QueryVariant> supplementalRetrievalVariants = supplementalRetrievalVariants(
            primaryRetrievalQuery, retrievalQuery, resolvedRetrievalQuestion,
            understandingText, queryResolution.rewritten());
        List<String> retrievalVariants = retrievalVariantQueries(
            primaryRetrievalQuery, supplementalRetrievalVariants);
        boolean retrievalQueryRewritten = queryResolution.rewritten()
            || !Objects.equals(retrievalQuery, queryResolution.query());
        String retrievalHistory = buildRetrievalHistory(
            recentMessages, queryResolution.contextDependent(), redactedTypes);
        // A rewritten query is already standalone. Reusing the raw previous topic
        // in semantic search would pull old evidence back into the new intent.
        String semanticRetrievalHistory = retrievalQueryRewritten
            ? null : retrievalHistory;
        RagRetrievalService.RetrievalResult retrieval;
        long retrievalStarted = System.nanoTime();
        boolean parentCompanyQuestion = isParentCompanyQuestion(understandingText)
            || (!Objects.equals(understandingText, retrievalQuery)
                && isParentCompanyQuestion(retrievalQuery));
        boolean outOfScopeQuestion = parentCompanyQuestion
            || isClearlyUnrelatedQuestion(understandingText)
            || (!Objects.equals(understandingText, retrievalQuery)
                && isClearlyUnrelatedQuestion(retrievalQuery));
        boolean ambiguousContractUpload = isAmbiguousContractUpload(nlpIntent, understandingText);
        if (outOfScopeQuestion) {
            retrieval = emptyRetrieval();
        } else if (compoundRenewalQuestion
                && (!hasText(safeProvidedContext) || mergeGlobalRetrieval)) {
            retrieval = mergeRetrievalEvidence(List.of(
                retrievalService.retrieve(
                    EXPIRED_ACCOUNT_LOGIN_QUERY, KNOWLEDGE_RETRIEVAL_FILTERS, true),
                retrievalService.retrieve(
                    EXPIRED_CONTRACT_ACCESS_QUERY, KNOWLEDGE_RETRIEVAL_FILTERS, true),
                retrievalService.retrieve(
                    EXPIRED_NEW_CONTRACT_QUERY, KNOWLEDGE_RETRIEVAL_FILTERS, true)));
            if (mergeGlobalRetrieval && hasText(safeProvidedContext)) {
                retrieval = retrievalService.mergeWithProvidedContext(
                    retrieval, safeProvidedContext, safeProvidedCitations);
            }
        } else if (ambiguousContractUpload
                && (!hasText(safeProvidedContext) || mergeGlobalRetrieval)) {
            RagRetrievalService.RetrievalResult launchEvidence =
                retrievalService.retrieve(
                    CONTRACT_FILE_LAUNCH_QUERY, KNOWLEDGE_RETRIEVAL_FILTERS, true);
            RagRetrievalService.RetrievalResult archiveEvidence =
                retrievalService.retrieve(
                    PAPER_CONTRACT_ARCHIVE_QUERY, KNOWLEDGE_RETRIEVAL_FILTERS, true);
            retrieval = mergeRetrievalEvidence(launchEvidence, archiveEvidence);
            if (mergeGlobalRetrieval && hasText(safeProvidedContext)) {
                retrieval = retrievalService.mergeWithProvidedContext(
                    retrieval, safeProvidedContext, safeProvidedCitations);
            }
        } else if (mergeGlobalRetrieval && hasText(safeProvidedContext)) {
            RagRetrievalService.RetrievalResult globalRetrieval = retrieveKnowledge(
                primaryRetrievalQuery, supplementalRetrievalVariants,
                semanticRetrievalHistory,
                safeModalityContext);
            retrieval = retrievalService.mergeWithProvidedContext(
                globalRetrieval, safeProvidedContext, safeProvidedCitations);
        } else {
            retrieval = hasText(safeProvidedContext)
                ? providedContextResult(safeProvidedContext, safeProvidedCitations)
                : retrieveKnowledge(primaryRetrievalQuery, supplementalRetrievalVariants,
                    semanticRetrievalHistory, null);
        }
        if (retrieval == null) {
            retrieval = emptyRetrieval();
        }
        boolean contextualQueryStillPrimary = queryResolution.rewritten()
            && Objects.equals(primaryRetrievalQuery, resolvedRetrievalQuestion);
        if (contextualQueryStillPrimary || companyIntroductionQuestion) {
            retrieval = requireContextualSynthesis(retrieval);
        }
        if (nlpIntent.requiresSpecificEvidence()
                && !hasSubjectSpecificEvidence(retrieval, nlpIntent.subject())) {
            retrieval = retrieval.answerable()
                ? markPartialEvidence(retrieval)
                : rejectMissingSpecificEvidence(retrieval);
        }
        retrievalLatencyMs = elapsedMillis(retrievalStarted);

        String replyText;
        String source;
        String answerStatus;
        String answerMode = "knowledge";
        String fallbackDecision = "not_needed";
        AnswerDecision answerDecision = AnswerDecision.NO_KNOWLEDGE;
        boolean directKnowledge = false;
        boolean outputBlocked = false;
        boolean highRiskNoKnowledge = false;
        ChatResponse aiResponse = null;
        String modelPrompt = null;
        List<PromptTrace> promptTraces = new ArrayList<>();
        List<String> modelProtocolViolations = new ArrayList<>();
        List<ModelProtocolViolation> modelProtocolViolationDetails = new ArrayList<>();
        List<ChatResponse> modelResponses = new ArrayList<>();
        List<Map<String, Object>> citations = new ArrayList<>(
            redactMaps(retrieval.citations(), redactedTypes));
        boolean companyIntroductionBacked = companyIntroductionQuestion
            && hasCompanyIntroductionEvidence(retrieval);
        String retrievalEvidence = evidenceText(retrieval);
        boolean conflictingScalarFacts = retrieval.answerable()
            && EvidenceConsistencyGuard.hasConflictingScalarFacts(
                understandingText, retrievalEvidence);
        String evidenceBackedReply = retrieval.answerable()
            ? evidenceBackedKnowledgeReply(understandingText, nlpIntent, retrieval) : null;
        String guardedKnowledgeReply = hasText(evidenceBackedReply)
            ? evidenceBackedReply
            : retrieval.answerable() ? guardedKnowledgeReply(
                safeText + "\n" + Objects.toString(retrievalQuery, "")
                    + "\n" + Objects.toString(retrievalHistory, ""),
                retrieval.directAnswer())
            : null;
        if (conflictingScalarFacts) {
            replyText = EVIDENCE_CONFLICT_REPLY;
            source = "rag_guardrail";
            answerStatus = "evidence_conflict";
            answerMode = "restricted";
            fallbackDecision = "conflicting_scalar_facts";
            answerDecision = AnswerDecision.HANDOFF;
        } else if (hasText(guardedKnowledgeReply)) {
            replyText = redact(guardedKnowledgeReply, redactedTypes);
            source = "rag_guardrail";
            answerStatus = "answered";
            answerMode = "knowledge";
            fallbackDecision = hasText(evidenceBackedReply)
                ? "evidence_consistency_guardrail" : "knowledge_guardrail";
            answerDecision = AnswerDecision.ANSWER;
            directKnowledge = true;
        } else if (!retrieval.answerable()) {
            citations.clear();
            if (outOfScopeQuestion) {
                replyText = redact(parentCompanyQuestion
                    ? outOfScopeReply : unrelatedReply, redactedTypes);
                source = "out_of_scope";
                answerStatus = "out_of_scope";
                answerMode = "restricted";
                fallbackDecision = parentCompanyQuestion
                    ? "parent_company_out_of_scope" : "out_of_scope";
                answerDecision = AnswerDecision.NO_KNOWLEDGE;
            } else {
                long modelStarted = System.nanoTime();
                NativeFallbackResponse fallback = nativeFallbackResponse(safeText, nlpIntent,
                    buildChatHistory(recentMessages, redactedTypes),
                    emotion, preferredModelId);
                if (fallback.aiResponse() != null) {
                    modelLatencyMs += elapsedMillis(modelStarted);
                }
                replyText = redact(fallback.replyText(), redactedTypes);
                source = fallback.source();
                answerStatus = fallback.answerStatus();
                answerMode = fallback.answerMode();
                fallbackDecision = fallback.decision();
                answerDecision = fallback.answerDecision();
                highRiskNoKnowledge = fallback.highRiskNoKnowledge();
                aiResponse = fallback.aiResponse();
                modelPrompt = fallback.modelPrompt();
                PromptTrace fallbackInvocation = addPromptTrace(
                    promptTraces, fallback.promptTrace());
                addModelResponse(modelResponses, fallback.aiResponse());
                addProtocolViolation(modelProtocolViolations,
                    modelProtocolViolationDetails, fallbackInvocation,
                    fallback.protocolViolation());
                if (fallback.recordUnmatchedQuestion()) unmatchedQuestionService.record(safeText);
            }
        } else if (retrieval.directAnswer() && hasText(retrieval.directAnswerText())) {
            replyText = redact(retrieval.directAnswerText(), redactedTypes);
            replyText = emotionService.adaptDeterministicReply(replyText, emotion);
            source = retrieval.decision() != null
                    && retrieval.decision().startsWith("structured_")
                ? "knowledge_qa" : "faq";
            answerStatus = "answered";
            answerDecision = AnswerDecision.ANSWER;
            directKnowledge = true;
        } else {
            String safeRetrievalContext = redact(retrieval.context(), redactedTypes);
            boolean partialEvidenceNeedsClarification = "partial_rag".equals(retrieval.decision())
                && shouldClarifyPartialEvidence(nlpIntent);
            String prompt = buildPrompt(
                buildChatHistory(recentMessages, redactedTypes), safeRetrievalContext,
                safeText, retrievalQuery, partialEvidenceNeedsClarification);
            modelPrompt = prompt;
            SystemPromptResolution systemPrompt = customerServiceSystemPrompt(
                emotion, safeText, nlpIntent, promptVersion);
            PromptTrace ragInvocation = addPromptTrace(promptTraces, systemPrompt.trace());
            long modelStarted = System.nanoTime();
            aiResponse = aiModelService.chatWithModel(
                prompt, systemPrompt.content(), preferredModelId);
            modelLatencyMs += elapsedMillis(modelStarted);
            addModelResponse(modelResponses, aiResponse);
            ModelAnswerSignalParser.ParsedAnswer parsedAnswer =
                MODEL_ANSWER_SIGNAL_PARSER.parse(aiResponse.getContent());
            addProtocolViolation(modelProtocolViolations,
                modelProtocolViolationDetails, ragInvocation, parsedAnswer.violation());
            boolean compoundAnswerRetry = isCompoundQuestion(safeText)
                && hasText(safeRetrievalContext);
            boolean evidenceAnswerRetry = retrieval.answerable()
                && hasText(safeRetrievalContext);
            boolean retryBackedAnswer = companyIntroductionBacked
                || compoundAnswerRetry || evidenceAnswerRetry;
            boolean ragRetryAttempted = false;
            if (aiResponse.isSuccess() && parsedAnswer.isNoAnswer()
                    && retryBackedAnswer) {
                ragRetryAttempted = true;
                String retryInstruction;
                String answeredRetryDecision;
                if (companyIntroductionBacked) {
                    retryInstruction = COMPANY_INTRODUCTION_ANSWER_INSTRUCTION;
                    answeredRetryDecision = "company_intro_retry";
                } else if (compoundAnswerRetry) {
                    retryInstruction = COMPOUND_ANSWER_INSTRUCTION;
                    answeredRetryDecision = "compound_answer_retry";
                } else {
                    retryInstruction = EVIDENCE_BACKED_ANSWER_RETRY_INSTRUCTION;
                    answeredRetryDecision = "evidence_answer_retry";
                }
                String retryPrompt = prompt + "\n再次确认：" + retryInstruction;
                PromptTrace retryInvocation = addPromptTrace(
                    promptTraces, systemPrompt.trace());
                long retryModelStarted = System.nanoTime();
                ChatResponse retryResponse = aiModelService.chatWithModel(
                    retryPrompt, systemPrompt.content(), preferredModelId);
                modelLatencyMs += elapsedMillis(retryModelStarted);
                addModelResponse(modelResponses, retryResponse);
                ModelAnswerSignalParser.ParsedAnswer retryParsedAnswer =
                    MODEL_ANSWER_SIGNAL_PARSER.parse(retryResponse.getContent());
                addProtocolViolation(modelProtocolViolations,
                    modelProtocolViolationDetails, retryInvocation,
                    retryParsedAnswer.violation());
                aiResponse = retryResponse;
                parsedAnswer = retryParsedAnswer;
                modelPrompt = retryPrompt;
                if (retryResponse.isSuccess() && retryParsedAnswer.isAnswer()) {
                    fallbackDecision = answeredRetryDecision;
                }
            }
            if (aiResponse.isSuccess() && parsedAnswer.isNoAnswer()) {
                citations.clear();
                replyText = redact(noAnswerReply, redactedTypes);
                source = "no_answer";
                answerStatus = "no_answer";
                answerMode = "restricted";
                fallbackDecision = ragRetryAttempted
                    ? "rag_abstained_after_retry" : "rag_abstained";
                answerDecision = AnswerDecision.NO_KNOWLEDGE;
                unmatchedQuestionService.record(safeText);
            } else if (aiResponse.isSuccess() && parsedAnswer.isAnswer()) {
                boolean partialAnswer = parsedAnswer.isPartial()
                    || "partial_rag".equals(retrieval.decision());
                replyText = redact(parsedAnswer.content(), redactedTypes);
                source = "rag_ai";
                answerStatus = "answered";
                answerMode = partialAnswer ? "partial" : "knowledge";
                answerDecision = partialAnswer
                    ? AnswerDecision.ANSWER_PARTIAL : AnswerDecision.ANSWER;
            } else {
                String reliableKnowledgeReply = structuredKnowledgeFallback(retrieval);
                if (hasText(reliableKnowledgeReply)) {
                    replyText = redact(reliableKnowledgeReply, redactedTypes);
                    source = "knowledge_qa";
                    answerStatus = "answered";
                    answerMode = "knowledge";
                    fallbackDecision = "model_failure_knowledge";
                    answerDecision = AnswerDecision.ANSWER;
                    directKnowledge = true;
                } else {
                    replyText = redact(errorReply, redactedTypes);
                    source = "error";
                    answerStatus = "error";
                    answerDecision = AnswerDecision.NO_KNOWLEDGE;
                    citations.clear();
                }
            }
        }

        if ("answered".equals(answerStatus) && retrieval.answerable()
                && EvidenceConsistencyGuard.hasUnsupportedEnumeratedFacts(
                    understandingText, retrievalEvidence, replyText)) {
            replyText = INSUFFICIENT_LIST_EVIDENCE_REPLY;
            source = "rag_guardrail";
            answerStatus = "insufficient_evidence";
            answerMode = "restricted";
            fallbackDecision = "unsupported_enumerated_facts";
            answerDecision = AnswerDecision.HANDOFF;
            directKnowledge = false;
        } else if ("answered".equals(answerStatus) && retrieval.answerable()
                && EvidenceConsistencyGuard.contradictsStandaloneAppBoundary(
                    retrievalEvidence, replyText)) {
            String reliableKnowledgeReply = verifiedProductUsageReply(
                understandingText, nlpIntent, retrieval);
            replyText = hasText(reliableKnowledgeReply)
                ? reliableKnowledgeReply : STANDALONE_APP_BOUNDARY_REPLY;
            source = "rag_guardrail";
            fallbackDecision = "standalone_app_boundary_guardrail";
            answerDecision = AnswerDecision.ANSWER;
            directKnowledge = true;
        } else if ("answered".equals(answerStatus) && retrieval.answerable()
                && EvidenceConsistencyGuard.hasUnsupportedProceduralSteps(
                    understandingText, retrievalEvidence, replyText)) {
            String reliableKnowledgeReply = structuredKnowledgeFallback(retrieval);
            replyText = hasText(reliableKnowledgeReply)
                ? reliableKnowledgeReply + "\n\n" + PROCEDURE_EVIDENCE_CLARIFICATION_REPLY
                : PROCEDURE_EVIDENCE_CLARIFICATION_REPLY;
            if (!hasText(reliableKnowledgeReply)) citations.clear();
            source = "rag_guardrail";
            answerStatus = "clarify";
            answerMode = "partial";
            fallbackDecision = "unsupported_procedural_steps";
            answerDecision = AnswerDecision.CLARIFY;
            directKnowledge = hasText(reliableKnowledgeReply);
        } else if ("answered".equals(answerStatus) && retrieval.answerable()
                && EvidenceConsistencyGuard.contradictsNegativeBoundary(
                    understandingText, retrievalEvidence, replyText)) {
            String reliableKnowledgeReply = structuredKnowledgeFallback(retrieval);
            replyText = hasText(reliableKnowledgeReply)
                ? reliableKnowledgeReply
                : EvidenceConsistencyGuard.repairNegativeBoundary(replyText);
            source = "rag_guardrail";
            fallbackDecision = "negative_boundary_consistency_guardrail";
            answerDecision = AnswerDecision.ANSWER;
            directKnowledge = hasText(reliableKnowledgeReply);
        }

        if ("answered".equals(answerStatus)) {
            replyText = enforceContractArchiveFormatConsistency(replyText, nlpIntent);
            replyText = stripCitationPresentation(replyText, citations);
            SafetyResult postCheck = safetyService.checkAiOutput(replyText);
            if (postCheck.isBlocked()) {
                outputBlocked = true;
                answerStatus = "blocked";
                source = "safety";
                answerDecision = AnswerDecision.HANDOFF;
                citations.clear();
                replyText = "REPLY_FIXED".equals(postCheck.getAction()) && hasText(postCheck.getReplyText())
                    ? redact(postCheck.getReplyText(), redactedTypes)
                    : "抱歉，该回答需要人工客服进一步确认，正在为您转接...";
                log.warn("Answer blocked by safety post-check: {}", postCheck.getHitRules());
            }
        }
        String richReply = RichReplyFormatter.format(replyText);
        replyText = PlainTextReplyFormatter.format(replyText);
        Map<String, Object> pendingClarification = pendingClarificationState(
            nlpIntent, replyText, answerStatus);

        List<KnowledgeImageService.ImageAttachment> attachments =
            replyAttachmentService.fromCitations(citations, "answered".equals(answerStatus));
        Map<String, Object> stageLatencies = stageLatencies(
            retrieval, retrievalLatencyMs, modelLatencyMs,
            System.currentTimeMillis() - started);

        Map<String, Object> response = new LinkedHashMap<>();
        boolean ragBackedResponse = ("faq".equals(source) || "knowledge_qa".equals(source)
            || "rag_ai".equals(source) || "rag_guardrail".equals(source))
            && !outputBlocked;
        response.put("reply", replyText);
        response.put("richReply", richReply);
        response.put("conversationId", conversation.getId());
        response.put("source", source);
        response.put("answerStatus", answerStatus);
        response.put("answerMode", answerMode);
        response.put("answerDecision", answerDecision.name());
        response.put("fallbackDecision", fallbackDecision);
        response.put("safetyPreCheck", safetyDetails(preCheck));
        response.put("confidence", retrieval.confidence());
        response.put("citations", List.copyOf(citations));
        response.put("attachments", attachments);
        response.put("ragSource", ragBackedResponse);
        response.put("ragContextChars", ragBackedResponse && retrieval.context() != null
            ? retrieval.context().length() : 0);
        response.put("rerankDiagnostics", retrieval.rerankDiagnostics());
        response.put("stageLatencies", stageLatencies);
        response.put("retrieval", retrievalDetails(retrieval, redactedTypes));
        response.put("retrievalContextUsed", hasText(retrievalHistory));
        response.put("retrievalHistoryUsed", hasText(semanticRetrievalHistory));
        response.put("retrievalPrimaryQuery", primaryRetrievalQuery);
        response.put("retrievalVariants", retrievalVariants);
        response.put("retrievalQuery", retrievalQuery);
        response.put("queryRewritten", retrievalQueryRewritten);
        response.put("queryContextDependent", queryResolution.contextDependent());
        response.put("contextResolutionApplied", queryResolution.rewritten());
        response.put("contextResolvedQuery", resolvedRetrievalQuestion);
        response.put("queryCorrectionApplied", !Objects.equals(safeText, understandingText));
        response.put("correctedQuery", understandingText);
        response.put("clarificationStateConsumed", queryResolution.clarificationResolved());
        response.put("clarificationResolutionSource", queryResolution.clarificationSource());
        if (pendingClarification != null) {
            response.put("pendingClarification", pendingClarification);
        }
        response.put("nlpIntent", nlpIntentDetails(nlpIntent));
        response.put("emotion", emotionDetails(emotion));
        response.put("promptVersion", promptVersion);
        addPromptTraceDetails(response, promptTraces);
        response.put("modelProtocolViolations", List.copyOf(modelProtocolViolations));
        response.put("modelProtocolViolationDetails",
            protocolViolationDetails(modelProtocolViolationDetails));
        addModelInvocationDetails(response, modelResponses);

        if (aiResponse != null) {
            response.put("model", aiResponse.getModel());
            response.put("providerCode", aiResponse.getProviderCode());
            response.put("inputTokens", aiResponse.getInputTokens());
            response.put("outputTokens", aiResponse.getOutputTokens());
            response.put("success", aiResponse.isSuccess());
        }

        boolean lowConfidence = !"native_ai".equals(source)
            && retrieval.answerable()
            && retrieval.confidence() < lowConfidenceThreshold
            && !companyIntroductionBacked;
        boolean lowConfidenceNeedsTransfer = lowConfidence
            && !"rag_guardrail".equals(source);
        boolean needsTransfer = outputBlocked
            || answerDecision == AnswerDecision.HANDOFF
            || ("no_answer".equals(answerStatus) && transferOnNoAnswer)
            || (highRiskNoKnowledge && nativeFallbackHighRiskTransfer)
            || (aiResponse != null && !"answered".equals(answerStatus)
                && (!aiResponse.isSuccess() || !hasText(aiResponse.getContent())))
            || ("answered".equals(answerStatus) && lowConfidenceNeedsTransfer)
            || emotion.shouldHandoff();
        response.put("needsTransfer", needsTransfer);
        response.put("lowConfidence", lowConfidence);

        Map<String, Object> messageMetadata = new LinkedHashMap<>();
        messageMetadata.put("answerStatus", answerStatus);
        messageMetadata.put("answerDecision", answerDecision.name());
        messageMetadata.put("confidence", retrieval.confidence());
        messageMetadata.put("citations", citations);
        messageMetadata.put("attachments", attachments);
        messageMetadata.put("source", source);
        messageMetadata.put("answerMode", answerMode);
        messageMetadata.put("fallbackDecision", fallbackDecision);
        messageMetadata.put("rerankDiagnostics", retrieval.rerankDiagnostics());
        messageMetadata.put("stageLatencies", stageLatencies);
        messageMetadata.put("retrievalVariants", retrievalVariants);
        messageMetadata.put("queryContextDependent", queryResolution.contextDependent());
        messageMetadata.put("contextResolutionApplied", queryResolution.rewritten());
        messageMetadata.put("contextResolvedQuery", resolvedRetrievalQuestion);
        messageMetadata.put("queryCorrectionApplied", !Objects.equals(safeText, understandingText));
        messageMetadata.put("correctedQuery", understandingText);
        messageMetadata.put("clarificationStateConsumed", queryResolution.clarificationResolved());
        messageMetadata.put("clarificationResolutionSource",
            queryResolution.clarificationSource());
        if (pendingClarification != null) {
            messageMetadata.put("pendingClarification", pendingClarification);
        }
        messageMetadata.put("nlpIntent", nlpIntentDetails(nlpIntent));
        messageMetadata.put("emotion", emotionDetails(emotion));
        messageMetadata.put("promptVersion", promptVersion);
        addPromptTraceDetails(messageMetadata, promptTraces);
        messageMetadata.put("modelProtocolViolations",
            List.copyOf(modelProtocolViolations));
        messageMetadata.put("modelProtocolViolationDetails",
            protocolViolationDetails(modelProtocolViolationDetails));
        addModelInvocationDetails(messageMetadata, modelResponses);
        messageMetadata.put("redactionApplied", !redactedTypes.isEmpty());
        String metadata = toJson(messageMetadata);
        BotMessage aiMessage = saveMessage(conversation.getId(), "ai", replyText, metadata,
            attachments.isEmpty() ? "text" : "mixed");
        saveReplyLog(aiMessage, safeText, retrieval, replyText, directKnowledge, aiResponse,
            source, answerStatus, answerDecision, fallbackDecision, nlpIntent, citations,
            redactedTypes, modelPrompt, promptVersion, promptTraces,
            modelProtocolViolations, modelProtocolViolationDetails, modelResponses,
            primaryRetrievalQuery, retrievalQuery, retrievalVariants,
            resolvedRetrievalQuestion, queryResolution.rewritten(),
            queryResolution.contextDependent(), retrievalQueryRewritten,
            hasText(retrievalHistory), hasText(semanticRetrievalHistory),
            stageLatencies, System.currentTimeMillis() - started);
        if (needsTransfer) {
            String reason = transferReason(outputBlocked, answerStatus, lowConfidenceNeedsTransfer,
                highRiskNoKnowledge, emotion);
            HandoffCoordinator.HandoffResult handoff = coordinateHandoff(
                conversation.getId(), reason,
                transferPriority(outputBlocked ? "P0" : "P1", emotion));
            addHandoffDetails(response, handoff);
            if (handoff.created()) {
                saveMessage(conversation.getId(), "system",
                    "【系统通知】已转人工，原因: " + reason, null);
            }
        }
        addRedactionDetails(response, redactedTypes);
        long totalLatencyMs = System.currentTimeMillis() - started;
        response.put("stageLatencies", stageLatencies(
            retrieval, retrievalLatencyMs, modelLatencyMs, totalLatencyMs));
        response.put("latencyMs", totalLatencyMs);
        return response;
    }

    private Map<String, Object> humanHandlingResponse(
            BotConversation conversation, String safeText, EmotionService.EmotionResult emotion,
            long started, Set<String> redactedTypes) {
        handoffCoordinator.recordUserMessage(conversation.getId(), safeText);
        Map<String, Object> response = new LinkedHashMap<>();
        String agentName = hasText(conversation.getAssignedAgentName())
            ? conversation.getAssignedAgentName() : "人工客服";
        response.put("reply", agentName + "已接手处理，您的新消息已同步给客服。");
        response.put("conversationId", conversation.getId());
        response.put("source", "human_handoff");
        response.put("answerStatus", "human_handling");
        response.put("answerDecision", AnswerDecision.HANDOFF.name());
        response.put("confidence", 1.0);
        response.put("citations", Collections.emptyList());
        response.put("attachments", Collections.emptyList());
        response.put("ragSource", false);
        response.put("needsTransfer", true);
        response.put("humanHandling", true);
        response.put("handoffStatus", conversation.getHandoffStatus());
        response.put("assignedAgentId", conversation.getAssignedAgentId());
        response.put("assignedAgentName", conversation.getAssignedAgentName());
        response.put("emotion", emotionDetails(emotion));
        addRedactionDetails(response, redactedTypes);
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    private Map<String, Object> waitingHandoffResponse(
            BotConversation conversation, String safeText, List<BotMessage> recentMessages,
            EmotionService.EmotionResult emotion, long started, Set<String> redactedTypes) {
        if (isExplicitHandoffRequest(safeText)) {
            return waitingHandoffReply(conversation,
                "人工客服请求已提交，请稍候等待客服接入。",
                "handoff_requested", true, emotion, started, redactedTypes);
        }

        if (isHandoffCancellation(safeText)) {
            boolean cancelled = handoffCoordinator.cancelWaitingHandoff(
                conversation.getId(), "用户取消等待人工客服");
            String reply = cancelled
                ? "已为您取消人工客服请求。后续如需帮助，您可以随时继续咨询。"
                : "当前人工客服已接手或请求状态已变化，暂时无法在线取消。您的消息已同步给客服。";
            return waitingHandoffReply(conversation, reply, "handoff_cancelled", false,
                emotion, started, redactedTypes);
        }

        if (isPreviousQuestionRequest(safeText)) {
            String previousQuestion = previousUserQuestion(recentMessages, safeText);
            String reply = hasText(previousQuestion)
                ? "您刚才咨询的是“" + previousQuestion + "”。人工客服请求已提交，您可以继续补充需求。"
                : "您刚才的咨询已提交给人工客服，您可以继续补充需要确认的内容。";
            return waitingHandoffReply(conversation, reply, "handoff_context", true,
                emotion, started, redactedTypes);
        }

        if (isHandoffExplanationRequest(safeText)) {
            return waitingHandoffReply(conversation,
                "该问题需要由人工客服核实确认。目前客服请求已提交，尚未有客服接手；"
                    + "您可以继续补充需求，我会一并同步。",
                "handoff_status", true, emotion, started, redactedTypes);
        }

        if (looksLikeHandoffSupplement(safeText)) {
            return waitingHandoffReply(conversation,
                "已收到您的补充信息，已同步到人工客服请求中。",
                "handoff_supplement", true, emotion, started, redactedTypes);
        }
        return null;
    }

    private Map<String, Object> manualHandoffResponse(
            BotConversation conversation, EmotionService.EmotionResult emotion,
            long started, Set<String> redactedTypes) {
        String reason = "客户主动请求人工客服";
        HandoffCoordinator.HandoffResult handoff = coordinateHandoff(
            conversation.getId(), reason, transferPriority("P1", emotion));
        boolean submitted = handoff.success();
        String reply = PlainTextReplyFormatter.format(submitted
            ? DEFAULT_MANUAL_HANDOFF_REPLY : DEFAULT_MANUAL_HANDOFF_FAILED_REPLY);
        String answerStatus = submitted ? "handoff_requested" : "handoff_failed";
        String handoffStatus = submitted ? "WAITING" : "SUBMISSION_FAILED";

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("answerStatus", answerStatus);
        metadata.put("answerDecision", AnswerDecision.HANDOFF.name());
        metadata.put("source", "handoff");
        metadata.put("answerMode", "restricted");
        metadata.put("fallbackDecision", "manual_handoff");
        metadata.put("handoffStatus", handoffStatus);
        metadata.put("emotion", emotionDetails(emotion));
        metadata.put("redactionApplied", !redactedTypes.isEmpty());
        saveMessage(conversation.getId(), "ai", reply, toJson(metadata));
        if (handoff.created()) {
            saveMessage(conversation.getId(), "system",
                "【系统通知】已转人工，原因: " + reason, null);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("conversationId", conversation.getId());
        response.put("source", "handoff");
        response.put("answerStatus", answerStatus);
        response.put("answerMode", "restricted");
        response.put("answerDecision", AnswerDecision.HANDOFF.name());
        response.put("fallbackDecision", "manual_handoff");
        response.put("confidence", 1.0);
        response.put("citations", Collections.emptyList());
        response.put("attachments", Collections.emptyList());
        response.put("ragSource", false);
        response.put("ragContextChars", 0);
        response.put("retrieval", Map.of(
            "decision", "handoff", "semanticAvailable", false,
            "candidates", Collections.emptyList()));
        response.put("retrievalContextUsed", false);
        response.put("needsTransfer", true);
        response.put("lowConfidence", false);
        response.put("humanHandling", false);
        response.put("handoffStatus", handoffStatus);
        addHandoffDetails(response, handoff);
        addRedactionDetails(response, redactedTypes);
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    private Map<String, Object> priceHandoffResponse(
            BotConversation conversation, String safeText, EmotionService.EmotionResult emotion,
            long started, Set<String> redactedTypes) {
        HandoffCoordinator.HandoffResult handoff = coordinateHandoff(
            conversation.getId(), "价格相关信息需要人工确认", "P1");
        boolean submitted = handoff.success();
        String reply = submitted
            ? configuredReply(priceHandoffReply, DEFAULT_PRICE_HANDOFF_REPLY)
            : configuredReply(priceHandoffFailedReply, DEFAULT_PRICE_HANDOFF_FAILED_REPLY);
        reply = PlainTextReplyFormatter.format(reply);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("answerStatus", "price_handoff");
        metadata.put("answerDecision", AnswerDecision.HANDOFF.name());
        metadata.put("source", "price_handoff");
        metadata.put("answerMode", "restricted");
        metadata.put("handoffReason", "价格相关信息需要人工确认");
        metadata.put("emotion", emotionDetails(emotion));
        metadata.put("redactionApplied", !redactedTypes.isEmpty());
        saveMessage(conversation.getId(), "ai", reply, toJson(metadata));
        if (handoff.created()) {
            saveMessage(conversation.getId(), "system",
                "【系统通知】已提交人工客服，原因: 价格相关信息需要人工确认", null);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("conversationId", conversation.getId());
        response.put("source", "price_handoff");
        response.put("answerStatus", "price_handoff");
        response.put("answerMode", "restricted");
        response.put("answerDecision", AnswerDecision.HANDOFF.name());
        response.put("fallbackDecision", "price_handoff");
        response.put("confidence", 1.0);
        response.put("citations", Collections.emptyList());
        response.put("attachments", Collections.emptyList());
        response.put("ragSource", false);
        response.put("needsTransfer", true);
        response.put("humanHandling", false);
        response.put("handoffStatus", submitted ? "WAITING" : "SUBMISSION_FAILED");
        response.put("emotion", emotionDetails(emotion));
        addHandoffDetails(response, handoff);
        addRedactionDetails(response, redactedTypes);
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    private Map<String, Object> annualSigningVolumeResponse(
            BotConversation conversation, SafetyResult preCheck, PriceVolumeBand volumeBand,
            EmotionService.EmotionResult emotion, long started, Set<String> redactedTypes) {
        if (volumeBand == PriceVolumeBand.STANDARD) {
            return priceQualificationResponse(
                conversation, preCheck, STANDARD_PACKAGE_PRICE_REPLY,
                "answered", AnswerDecision.ANSWER, "standard_package_pricing",
                emotion, started, redactedTypes);
        }
        if (volumeBand == PriceVolumeBand.ENTERPRISE) {
            return priceQualificationResponse(
                conversation, preCheck, ENTERPRISE_PRICE_REPLY,
                "clarify", AnswerDecision.CLARIFY, "enterprise_pricing_handoff_offered",
                emotion, started, redactedTypes);
        }
        return priceQualificationResponse(
            conversation, preCheck, PRICE_VOLUME_QUESTION,
            "clarify", AnswerDecision.CLARIFY, "price_volume_requested",
            emotion, started, redactedTypes);
    }

    private Map<String, Object> priceQualificationResponse(
            BotConversation conversation, SafetyResult preCheck, String reply,
            String answerStatus, AnswerDecision answerDecision, String fallbackDecision,
            EmotionService.EmotionResult emotion, long started, Set<String> redactedTypes) {
        reply = PlainTextReplyFormatter.format(reply);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("answerStatus", answerStatus);
        metadata.put("answerDecision", answerDecision.name());
        metadata.put("source", "price_qualification");
        metadata.put("answerMode", "policy");
        metadata.put("fallbackDecision", fallbackDecision);
        metadata.put("emotion", emotionDetails(emotion));
        metadata.put("redactionApplied", !redactedTypes.isEmpty());
        saveMessage(conversation.getId(), "ai", reply, toJson(metadata));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("conversationId", conversation.getId());
        response.put("source", "price_qualification");
        response.put("answerStatus", answerStatus);
        response.put("answerMode", "policy");
        response.put("answerDecision", answerDecision.name());
        response.put("fallbackDecision", fallbackDecision);
        response.put("safetyPreCheck", safetyDetails(preCheck));
        response.put("confidence", 1.0);
        response.put("citations", Collections.emptyList());
        response.put("attachments", Collections.emptyList());
        response.put("ragSource", false);
        response.put("ragContextChars", 0);
        response.put("retrieval", Map.of(
            "decision", "price_qualification", "semanticAvailable", false,
            "candidates", Collections.emptyList()));
        response.put("retrievalContextUsed", false);
        response.put("retrievalHistoryUsed", false);
        response.put("needsTransfer", false);
        response.put("lowConfidence", false);
        response.put("humanHandling", false);
        response.put("emotion", emotionDetails(emotion));
        addRedactionDetails(response, redactedTypes);
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    private Map<String, Object> waitingHandoffReply(
            BotConversation conversation, String reply, String answerStatus, boolean waiting,
            EmotionService.EmotionResult emotion, long started, Set<String> redactedTypes) {
        reply = PlainTextReplyFormatter.format(reply);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("conversationId", conversation.getId());
        response.put("source", "handoff_waiting");
        response.put("answerStatus", answerStatus);
        response.put("answerMode", "handoff_waiting");
        response.put("answerDecision", waiting
            ? AnswerDecision.HANDOFF.name() : AnswerDecision.ANSWER.name());
        response.put("confidence", 1.0);
        response.put("citations", Collections.emptyList());
        response.put("attachments", Collections.emptyList());
        response.put("ragSource", false);
        response.put("needsTransfer", waiting);
        response.put("humanHandling", false);
        response.put("handoffStatus", waiting ? "WAITING" : "CANCELLED");
        response.put("emotion", emotionDetails(emotion));
        saveMessage(conversation.getId(), "ai", reply, toJson(Map.of(
            "answerStatus", answerStatus,
            "answerDecision", waiting
                ? AnswerDecision.HANDOFF.name() : AnswerDecision.ANSWER.name(),
            "source", "handoff_waiting",
            "emotion", emotionDetails(emotion),
            "redactionApplied", !redactedTypes.isEmpty())));
        addRedactionDetails(response, redactedTypes);
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    private Map<String, Object> previousQuestionResponse(
            BotConversation conversation, SafetyResult preCheck,
            List<BotMessage> recentMessages, String currentText,
            EmotionService.EmotionResult emotion, long started,
            Set<String> redactedTypes, String promptVersion) {
        String previousQuestion = previousUserQuestion(recentMessages, currentText);
        String reply = hasText(previousQuestion)
            ? "您上一条问的是：“" + previousQuestion + "”。"
            : "当前会话里还没有找到上一条问题。";
        String source = "conversation_context";
        String answerStatus = "answered";
        boolean outputBlocked = false;
        SafetyResult postCheck = safetyService.checkAiOutput(reply);
        if (postCheck.isBlocked()) {
            outputBlocked = true;
            source = "safety";
            answerStatus = "blocked";
            reply = "REPLY_FIXED".equals(postCheck.getAction()) && hasText(postCheck.getReplyText())
                ? redact(postCheck.getReplyText(), redactedTypes)
                : "抱歉，上一条问题包含需要谨慎处理的内容，正在为您转接人工客服。";
        }
        reply = PlainTextReplyFormatter.format(reply);
        boolean needsTransfer = outputBlocked || emotion.shouldHandoff();
        AnswerDecision decision = needsTransfer
            ? AnswerDecision.HANDOFF : AnswerDecision.ANSWER;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("answerStatus", answerStatus);
        metadata.put("answerDecision", decision.name());
        metadata.put("source", source);
        metadata.put("answerMode", "conversation_context");
        metadata.put("fallbackDecision", "previous_question");
        metadata.put("emotion", emotionDetails(emotion));
        metadata.put("promptVersion", promptVersion);
        metadata.put("redactionApplied", !redactedTypes.isEmpty());
        saveMessage(conversation.getId(), "ai", reply, toJson(metadata));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("conversationId", conversation.getId());
        response.put("source", source);
        response.put("answerStatus", answerStatus);
        response.put("answerMode", "conversation_context");
        response.put("answerDecision", decision.name());
        response.put("fallbackDecision", "previous_question");
        response.put("safetyPreCheck", safetyDetails(preCheck));
        response.put("confidence", 1.0);
        response.put("citations", Collections.emptyList());
        response.put("attachments", Collections.emptyList());
        response.put("ragSource", false);
        response.put("ragContextChars", 0);
        response.put("retrieval", Map.of(
            "decision", "conversation_context", "semanticAvailable", false,
            "candidates", Collections.emptyList()));
        response.put("retrievalContextUsed", false);
        response.put("needsTransfer", needsTransfer);
        response.put("lowConfidence", false);
        response.put("emotion", emotionDetails(emotion));
        response.put("promptVersion", promptVersion);
        if (needsTransfer) {
            String reason = appendEmotionReason(
                outputBlocked ? "会话上下文回复触发安全规则" : null, emotion);
            HandoffCoordinator.HandoffResult handoff = coordinateHandoff(
                conversation.getId(), reason,
                transferPriority(outputBlocked ? "P0" : "P1", emotion));
            addHandoffDetails(response, handoff);
            if (handoff.created()) {
                saveMessage(conversation.getId(), "system",
                    "【系统通知】已转人工，原因: " + reason, null);
            }
        }
        addRedactionDetails(response, redactedTypes);
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    private Map<String, Object> toolResponse(
            BotConversation conversation, SafetyResult preCheck,
            BusinessToolOrchestrator.ToolRoutingResult toolRouting,
            EmotionService.EmotionResult emotion,
            long started, Set<String> redactedTypes) {
        String reply = redact(toolRouting.reply(), redactedTypes);
        reply = emotionService.adaptDeterministicReply(reply, emotion);
        String answerStatus = toolRouting.status();
        boolean needsTransfer = toolRouting.needsTransfer() || emotion.shouldHandoff();
        boolean outputBlocked = false;
        SafetyResult postCheck = safetyService.checkAiOutput(reply);
        if (postCheck.isBlocked()) {
            outputBlocked = true;
            needsTransfer = true;
            answerStatus = "blocked";
            reply = "REPLY_FIXED".equals(postCheck.getAction()) && hasText(postCheck.getReplyText())
                ? redact(postCheck.getReplyText(), redactedTypes)
                : "抱歉，该业务查询结果需要人工客服进一步确认，正在为您转接...";
        }
        reply = PlainTextReplyFormatter.format(reply);
        AnswerDecision toolDecision = needsTransfer
            ? AnswerDecision.HANDOFF : AnswerDecision.ANSWER;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("answerStatus", answerStatus);
        metadata.put("answerDecision", toolDecision.name());
        metadata.put("source", "tool");
        metadata.put("executions", toolRouting.executions());
        metadata.put("emotion", emotionDetails(emotion));
        metadata.put("redactionApplied", !redactedTypes.isEmpty());
        saveMessage(conversation.getId(), "ai", reply, toJson(metadata));

        Map<String, Object> toolDetails = new LinkedHashMap<>();
        toolDetails.put("status", toolRouting.status());
        toolDetails.put("executions", toolRouting.executions());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("conversationId", conversation.getId());
        response.put("source", "tool");
        response.put("answerStatus", answerStatus);
        response.put("answerDecision", toolDecision.name());
        response.put("safetyPreCheck", safetyDetails(preCheck));
        response.put("confidence", "answered".equals(answerStatus) ? 1.0 : 0.0);
        response.put("citations", Collections.emptyList());
        response.put("attachments", Collections.emptyList());
        response.put("ragSource", false);
        response.put("ragContextChars", 0);
        response.put("retrieval", Map.of(
            "decision", "tool", "semanticAvailable", false,
            "candidates", Collections.emptyList()));
        response.put("retrievalContextUsed", false);
        response.put("tool", toolDetails);
        response.put("needsTransfer", needsTransfer);
        response.put("lowConfidence", false);
        response.put("emotion", emotionDetails(emotion));

        if (needsTransfer) {
            String baseReason = outputBlocked ? "业务查询结果触发安全规则"
                : toolRouting.needsTransfer()
                    ? hasText(toolRouting.transferReason())
                        ? toolRouting.transferReason() : "业务查询需要人工处理"
                    : null;
            String reason = appendEmotionReason(baseReason, emotion);
            HandoffCoordinator.HandoffResult handoff = coordinateHandoff(
                conversation.getId(), reason,
                transferPriority(outputBlocked ? "P0" : "P1", emotion));
            addHandoffDetails(response, handoff);
            if (handoff.created()) {
                saveMessage(conversation.getId(), "system",
                    "【系统通知】已转人工，原因: " + reason, null);
            }
        }
        addRedactionDetails(response, redactedTypes);
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    private Map<String, Object> intentResponse(
            BotConversation conversation, SafetyResult preCheck,
            IntentService.IntentMatch intentMatch,
            EmotionService.EmotionResult emotion,
            long started, Set<String> redactedTypes) {
        String reply = redact(intentMatch.reply(), redactedTypes);
        reply = emotionService.adaptDeterministicReply(reply, emotion);
        String answerStatus = "answered";
        String source = "intent";
        boolean outputBlocked = false;
        SafetyResult postCheck = safetyService.checkAiOutput(reply);
        if (postCheck.isBlocked()) {
            outputBlocked = true;
            answerStatus = "blocked";
            source = "safety";
            reply = "REPLY_FIXED".equals(postCheck.getAction()) && hasText(postCheck.getReplyText())
                ? redact(postCheck.getReplyText(), redactedTypes)
                : "抱歉，该回复需要人工客服进一步确认，正在为您转接...";
        }
        reply = PlainTextReplyFormatter.format(reply);
        AnswerDecision intentDecision = outputBlocked || emotion.shouldHandoff()
            ? AnswerDecision.HANDOFF : AnswerDecision.ANSWER;

        Map<String, Object> intentDetails = new LinkedHashMap<>();
        intentDetails.put("id", intentMatch.intentId());
        intentDetails.put("name", intentMatch.intentName());
        intentDetails.put("matchedKeyword", intentMatch.keyword());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("answerStatus", answerStatus);
        metadata.put("answerDecision", intentDecision.name());
        metadata.put("source", source);
        metadata.put("answerMode", "intent");
        metadata.put("intent", intentDetails);
        metadata.put("emotion", emotionDetails(emotion));
        metadata.put("redactionApplied", !redactedTypes.isEmpty());
        saveMessage(conversation.getId(), "ai", reply, toJson(metadata));

        boolean needsTransfer = outputBlocked || emotion.shouldHandoff();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("conversationId", conversation.getId());
        response.put("source", source);
        response.put("answerStatus", answerStatus);
        response.put("answerMode", "intent");
        response.put("answerDecision", intentDecision.name());
        response.put("fallbackDecision", "not_needed");
        response.put("safetyPreCheck", safetyDetails(preCheck));
        response.put("confidence", 1.0);
        response.put("citations", Collections.emptyList());
        response.put("attachments", Collections.emptyList());
        response.put("ragSource", false);
        response.put("ragContextChars", 0);
        response.put("retrieval", Map.of(
            "decision", "intent", "semanticAvailable", false,
            "candidates", Collections.emptyList()));
        response.put("retrievalContextUsed", false);
        response.put("intent", intentDetails);
        response.put("needsTransfer", needsTransfer);
        response.put("lowConfidence", false);
        response.put("emotion", emotionDetails(emotion));

        if (needsTransfer) {
            String reason = appendEmotionReason(
                outputBlocked ? "意图回复触发安全规则" : null, emotion);
            HandoffCoordinator.HandoffResult handoff = coordinateHandoff(
                conversation.getId(), reason,
                transferPriority(outputBlocked ? "P0" : "P1", emotion));
            addHandoffDetails(response, handoff);
            if (handoff.created()) {
                saveMessage(conversation.getId(), "system",
                    "【系统通知】已转人工，原因: " + reason, null);
            }
        }
        addRedactionDetails(response, redactedTypes);
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    private Map<String, Object> basicConversationResponse(
            BotConversation conversation, SafetyResult preCheck,
            BasicConversationIntent basicIntent, EmotionService.EmotionResult emotion,
            long started, Set<String> redactedTypes, String promptVersion) {
        String reply = emotionService.adaptDeterministicReply(basicIntent.reply(), emotion);
        String answerStatus = "answered";
        String source = "basic_conversation";
        boolean outputBlocked = false;
        SafetyResult postCheck = safetyService.checkAiOutput(reply);
        if (postCheck.isBlocked()) {
            outputBlocked = true;
            answerStatus = "blocked";
            source = "safety";
            reply = "REPLY_FIXED".equals(postCheck.getAction()) && hasText(postCheck.getReplyText())
                ? redact(postCheck.getReplyText(), redactedTypes)
                : "抱歉，该回复需要人工客服进一步确认，正在为您转接...";
        }
        reply = PlainTextReplyFormatter.format(reply);
        AnswerDecision decision = outputBlocked || emotion.shouldHandoff()
            ? AnswerDecision.HANDOFF : AnswerDecision.ANSWER;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("answerStatus", answerStatus);
        metadata.put("answerDecision", decision.name());
        metadata.put("source", source);
        metadata.put("answerMode", "basic");
        metadata.put("basicIntent", basicIntent.code());
        metadata.put("emotion", emotionDetails(emotion));
        metadata.put("promptVersion", promptVersion);
        metadata.put("redactionApplied", !redactedTypes.isEmpty());
        saveMessage(conversation.getId(), "ai", reply, toJson(metadata));

        boolean needsTransfer = outputBlocked || emotion.shouldHandoff();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("conversationId", conversation.getId());
        response.put("source", source);
        response.put("answerStatus", answerStatus);
        response.put("answerMode", "basic");
        response.put("answerDecision", decision.name());
        response.put("fallbackDecision", "basic_" + basicIntent.code());
        response.put("basicIntent", basicIntent.code());
        response.put("safetyPreCheck", safetyDetails(preCheck));
        response.put("confidence", 1.0);
        response.put("citations", Collections.emptyList());
        response.put("attachments", Collections.emptyList());
        response.put("ragSource", false);
        response.put("ragContextChars", 0);
        response.put("retrieval", Map.of(
            "decision", "basic_conversation", "semanticAvailable", false,
            "candidates", Collections.emptyList()));
        response.put("retrievalContextUsed", false);
        response.put("retrievalHistoryUsed", false);
        response.put("needsTransfer", needsTransfer);
        response.put("lowConfidence", false);
        response.put("emotion", emotionDetails(emotion));
        response.put("promptVersion", promptVersion);

        if (needsTransfer) {
            String reason = appendEmotionReason(
                outputBlocked ? "基础会话回复触发安全规则" : null, emotion);
            HandoffCoordinator.HandoffResult handoff = coordinateHandoff(
                conversation.getId(), reason,
                transferPriority(outputBlocked ? "P0" : "P1", emotion));
            addHandoffDetails(response, handoff);
            if (handoff.created()) {
                saveMessage(conversation.getId(), "system",
                    "【系统通知】已转人工，原因: " + reason, null);
            }
        }
        addRedactionDetails(response, redactedTypes);
        response.put("latencyMs", System.currentTimeMillis() - started);
        return response;
    }

    private Map<String, Object> blockedResponse(BotConversation conversation,
                                                SafetyResult preCheck,
                                                EmotionService.EmotionResult emotion,
                                                long started,
                                                Set<String> redactedTypes) {
        String reply;
        if ("HANDOFF".equals(preCheck.getAction()) && hasText(preCheck.getReplyText())) {
            reply = redact(preCheck.getReplyText(), redactedTypes);
        } else if ("HANDOFF".equals(preCheck.getAction())) {
            reply = "您的问题需要人工客服协助处理，正在为您转接...";
        } else if ("REPLY_FIXED".equals(preCheck.getAction()) && hasText(preCheck.getReplyText())) {
            reply = redact(preCheck.getReplyText(), redactedTypes);
        } else {
            reply = "抱歉，我无法回答这个问题。如有需要，可以转接人工客服。";
        }
        reply = PlainTextReplyFormatter.format(reply);

        HandoffCoordinator.HandoffResult handoff = null;
        boolean needsTransfer = "HANDOFF".equals(preCheck.getAction()) || emotion.shouldHandoff();
        AnswerDecision blockedDecision = needsTransfer
            ? AnswerDecision.HANDOFF : AnswerDecision.NO_KNOWLEDGE;
        saveMessage(conversation.getId(), "ai", reply,
            toJson(Map.of("answerStatus", "blocked",
                "answerDecision", blockedDecision.name(),
                "citations", Collections.emptyList(),
                "emotion", emotionDetails(emotion))));
        if (needsTransfer) {
            String baseReason = preCheck.getHitRules() != null && !preCheck.getHitRules().isEmpty()
                ? preCheck.getHitRules().get(0) : "触发了转人工规则";
            String reason = appendEmotionReason(baseReason, emotion);
            handoff = coordinateHandoff(conversation.getId(), reason,
                transferPriority("HANDOFF".equals(preCheck.getAction()) ? "P0" : "P1", emotion));
            if (handoff.created()) {
                saveMessage(conversation.getId(), "system", "【系统通知】转人工原因: " + reason, null);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply);
        result.put("conversationId", conversation.getId());
        result.put("source", "safety");
        result.put("answerStatus", "blocked");
        result.put("answerDecision", blockedDecision.name());
        result.put("safetyPreCheck", safetyDetails(preCheck));
        result.put("confidence", 0.0);
        result.put("citations", Collections.emptyList());
        result.put("attachments", Collections.emptyList());
        result.put("ragSource", false);
        result.put("needsTransfer", needsTransfer);
        result.put("safetyAction", preCheck.getAction());
        result.put("emotion", emotionDetails(emotion));
        if (handoff != null) addHandoffDetails(result, handoff);
        addRedactionDetails(result, redactedTypes);
        result.put("latencyMs", System.currentTimeMillis() - started);
        return result;
    }

    private Map<String, Object> safetyDetails(SafetyResult safety) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("blocked", safety.isBlocked());
        details.put("action", safety.getAction());
        details.put("hitRules", safety.getHitRules() == null
            ? Collections.emptyList() : safety.getHitRules());
        return details;
    }

    private Map<String, Object> stageLatencies(
            RagRetrievalService.RetrievalResult retrieval,
            long retrievalLatencyMs, long modelLatencyMs, long dialogTotalMs) {
        Map<String, Object> timings = new LinkedHashMap<>();
        if (retrieval != null && retrieval.stageLatencies() != null) {
            timings.putAll(retrieval.stageLatencies());
        }
        timings.putIfAbsent("embeddingMs", 0L);
        timings.putIfAbsent("vectorSearchMs", 0L);
        timings.putIfAbsent("sparseSearchMs", 0L);
        timings.putIfAbsent("rerankMs", 0L);
        timings.put("retrievalMs", Math.max(0L, retrievalLatencyMs));
        timings.put("modelMs", Math.max(0L, modelLatencyMs));
        timings.put("dialogTotalMs", Math.max(0L, dialogTotalMs));
        timings.put("otherMs", Math.max(0L,
            dialogTotalMs - retrievalLatencyMs - modelLatencyMs));
        return Collections.unmodifiableMap(timings);
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private RagRetrievalService.RetrievalResult providedContextResult(
            String context, List<Map<String, Object>> providedCitations) {
        List<Map<String, Object>> citations = providedCitations == null || providedCitations.isEmpty()
            ? retrievalService.citationsForProvidedContext(context)
            : List.copyOf(providedCitations);
        return new RagRetrievalService.RetrievalResult(
            true, false, null, context.trim(), 1.0, "provided_context",
            false, citations, Collections.emptyList());
    }

    private RagRetrievalService.RetrievalResult emptyRetrieval() {
        return new RagRetrievalService.RetrievalResult(
            false, false, null, null, 0, "no_answer",
            false, Collections.emptyList(), Collections.emptyList());
    }

    private RagRetrievalService.RetrievalResult retrieveKnowledge(
            String primaryQuery, List<QueryVariant> supplementalVariants,
            String conversationContext, String modalityContext) {
        String primary = hasText(primaryQuery) ? primaryQuery.trim() : "";
        List<QueryVariant> variants = supplementalVariants == null
            ? Collections.emptyList() : supplementalVariants;
        if (variants.isEmpty()) {
            if (!hasText(conversationContext) && !hasText(modalityContext)) {
                return retrievalService.retrieve(
                    primary, KNOWLEDGE_RETRIEVAL_FILTERS, true);
            }
            return retrievalService.retrieve(primary, conversationContext, modalityContext,
                KNOWLEDGE_RETRIEVAL_FILTERS, true);
        }
        return retrievalService.retrieve(primary, conversationContext, modalityContext,
            KNOWLEDGE_RETRIEVAL_FILTERS, variants, true);
    }

    private List<QueryVariant> supplementalRetrievalVariants(
            String primaryQuery, String intentQuery, String contextResolvedQuery,
            String originalQuestion, boolean contextResolutionApplied) {
        List<QueryVariant> variants = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (hasText(primaryQuery)) seen.add(primaryQuery.trim());
        if (contextResolutionApplied) {
            addRetrievalVariant(variants, seen, contextResolvedQuery,
                CONTEXT_RESOLUTION_RETRIEVAL_WEIGHT, "context_resolved");
            addRetrievalVariant(variants, seen, originalQuestion,
                CONTEXT_FOLLOW_UP_RETRIEVAL_WEIGHT, "context_follow_up");
        }
        addRetrievalVariant(variants, seen, intentQuery,
            INTENT_REWRITE_RETRIEVAL_WEIGHT, "intent_rewrite");
        return List.copyOf(variants);
    }

    private void addRetrievalVariant(List<QueryVariant> variants, Set<String> seen,
                                     String query, double weight, String purpose) {
        if (!hasText(query)) return;
        String normalized = query.trim();
        if (!seen.add(normalized)) return;
        variants.add(new QueryVariant(normalized, weight, purpose, false));
    }

    private List<String> retrievalVariantQueries(
            String primaryQuery, List<QueryVariant> supplementalVariants) {
        List<String> queries = new ArrayList<>();
        if (hasText(primaryQuery)) queries.add(primaryQuery.trim());
        if (supplementalVariants != null) {
            supplementalVariants.stream().map(QueryVariant::query)
                .filter(query -> !queries.contains(query))
                .forEach(queries::add);
        }
        return List.copyOf(queries);
    }

    private RagRetrievalService.RetrievalResult requireContextualSynthesis(
            RagRetrievalService.RetrievalResult retrieval) {
        if (retrieval == null || !retrieval.directAnswer()) return retrieval;
        return new RagRetrievalService.RetrievalResult(
            retrieval.answerable(), false, null, retrieval.context(), retrieval.confidence(),
            "contextual_rag", retrieval.semanticAvailable(), retrieval.citations(),
            retrieval.candidates(), retrieval.rerankDiagnostics(), retrieval.stageLatencies());
    }

    private boolean isAmbiguousContractUpload(
            NlpIntentClassifier.IntentAnalysis nlpIntent, String question) {
        if (nlpIntent.intentCode()
                != NlpIntentClassifier.IntentCode.CONTRACT_SIGNING_OPERATION
                || !nlpIntent.needsClarification() || !hasText(question)) {
            return false;
        }
        String normalized = question.replaceAll("[\\s，。！？?、；;：:（）()【】\\[\\]“”\"']+", "");
        if (!normalized.contains("上传")) return false;
        return normalized.contains("已经有合同") || normalized.contains("已有合同")
            || normalized.contains("现成合同") || normalized.contains("合同文件")
            || normalized.contains("有一份合同");
    }

    private RagRetrievalService.RetrievalResult mergeRetrievalEvidence(
            RagRetrievalService.RetrievalResult launchEvidence,
            RagRetrievalService.RetrievalResult archiveEvidence) {
        boolean hasLaunchEvidence = launchEvidence != null && launchEvidence.answerable();
        boolean hasArchiveEvidence = archiveEvidence != null && archiveEvidence.answerable();
        if (hasLaunchEvidence && hasArchiveEvidence && hasText(archiveEvidence.context())) {
            return retrievalService.mergeWithProvidedContext(
                launchEvidence, archiveEvidence.context(), archiveEvidence.citations());
        }
        if (hasLaunchEvidence) return launchEvidence;
        if (hasArchiveEvidence) return archiveEvidence;
        return emptyRetrieval();
    }

    private RagRetrievalService.RetrievalResult mergeRetrievalEvidence(
            List<RagRetrievalService.RetrievalResult> evidenceResults) {
        List<RagRetrievalService.RetrievalResult> answerable = evidenceResults == null
            ? Collections.emptyList()
            : evidenceResults.stream()
                .filter(Objects::nonNull)
                .filter(RagRetrievalService.RetrievalResult::answerable)
                .sorted((left, right) -> Double.compare(right.confidence(), left.confidence()))
                .toList();
        if (answerable.isEmpty()) return emptyRetrieval();

        RagRetrievalService.RetrievalResult merged = answerable.get(0);
        boolean semanticAvailable = merged.semanticAvailable();
        for (int i = 1; i < answerable.size(); i++) {
            RagRetrievalService.RetrievalResult next = answerable.get(i);
            semanticAvailable = semanticAvailable || next.semanticAvailable();
            String context = evidenceContext(next);
            if (hasText(context)) {
                merged = retrievalService.mergeWithProvidedContext(
                    merged, context, next.citations());
            }
        }
        return new RagRetrievalService.RetrievalResult(
            true, false, null, evidenceContext(merged), answerable.get(0).confidence(),
            "compound_rag", semanticAvailable, merged.citations(), merged.candidates(),
            merged.rerankDiagnostics(), merged.stageLatencies());
    }

    private String evidenceContext(RagRetrievalService.RetrievalResult retrieval) {
        if (retrieval == null) return null;
        if (hasText(retrieval.context())) return retrieval.context();
        if (hasText(retrieval.directAnswerText())) {
            return "【已核实知识】\n" + retrieval.directAnswerText();
        }
        return null;
    }

    private boolean hasSubjectSpecificEvidence(
            RagRetrievalService.RetrievalResult retrieval, String subject) {
        if (retrieval == null || !retrieval.answerable() || !hasText(subject)) return false;
        if (containsEvidenceTerm(retrieval.context(), subject)
                || containsEvidenceTerm(retrieval.directAnswerText(), subject)) {
            return true;
        }
        return evidenceMapsContain(retrieval.citations(), subject)
            || evidenceMapsContain(retrieval.candidates(), subject);
    }

    private boolean evidenceMapsContain(List<Map<String, Object>> evidence, String subject) {
        if (evidence == null || evidence.isEmpty()) return false;
        List<String> evidenceKeys = List.of(
            "title", "snippet", "content", "question", "answer", "text");
        for (Map<String, Object> item : evidence) {
            if (item == null) continue;
            for (String key : evidenceKeys) {
                if (containsEvidenceTerm(Objects.toString(item.get(key), null), subject)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsEvidenceTerm(String evidence, String subject) {
        if (!hasText(evidence) || !hasText(subject)) return false;
        String normalizedEvidence = evidence.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String normalizedSubject = subject.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return normalizedEvidence.contains(normalizedSubject);
    }

    private String enforceContractArchiveFormatConsistency(
            String reply, NlpIntentClassifier.IntentAnalysis nlpIntent) {
        if (!hasText(reply) || nlpIntent.intentCode()
                != NlpIntentClassifier.IntentCode.CONTRACT_SIGNING_OPERATION) {
            return reply;
        }
        String normalized = reply.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        boolean explicitPdfOfdOnly = (normalized.contains("仅支持")
            || normalized.contains("只支持"))
            && normalized.contains("pdf") && normalized.contains("ofd");
        if (!explicitPdfOfdOnly) return reply;
        return reply.replaceAll(
            "(?i)PDF\\s*(?:或|、|/|和)\\s*(?:图片|图像|JPG|JPEG|PNG)(?:格式|文件)?",
            "PDF或OFD");
    }

    private RagRetrievalService.RetrievalResult rejectMissingSpecificEvidence(
            RagRetrievalService.RetrievalResult retrieval) {
        if (retrieval == null) return emptyRetrieval();
        return new RagRetrievalService.RetrievalResult(
            false, false, null, null, retrieval.confidence(),
            "missing_specific_evidence", retrieval.semanticAvailable(),
            Collections.emptyList(), retrieval.candidates(), retrieval.rerankDiagnostics(),
            retrieval.stageLatencies());
    }

    private RagRetrievalService.RetrievalResult markPartialEvidence(
            RagRetrievalService.RetrievalResult retrieval) {
        return new RagRetrievalService.RetrievalResult(
            true, false, null, retrieval.context(), retrieval.confidence(),
            "partial_rag", retrieval.semanticAvailable(), retrieval.citations(),
            retrieval.candidates(), retrieval.rerankDiagnostics(), retrieval.stageLatencies());
    }

    private Map<String, Object> nlpIntentDetails(
            NlpIntentClassifier.IntentAnalysis analysis) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("intentCode", analysis.intentCode().name());
        details.put("domain", analysis.domain());
        details.put("riskLevel", analysis.riskLevel().name());
        details.put("entities", analysis.entities());
        details.put("actions", analysis.actions());
        details.put("needsClarification", analysis.needsClarification());
        details.put("matchedSignals", analysis.matchedSignals());
        details.put("subject", analysis.subject());
        details.put("requiresSpecificEvidence", analysis.requiresSpecificEvidence());
        details.put("generallySupportedContractType", analysis.generallySupportedContractType());
        return details;
    }

    private Map<String, Object> retrievalDetails(RagRetrievalService.RetrievalResult retrieval,
                                                 Set<String> redactedTypes) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("decision", retrieval.decision());
        details.put("semanticAvailable", retrieval.semanticAvailable());
        details.put("rerankDiagnostics", retrieval.rerankDiagnostics());
        details.put("decisionDiagnostics", retrieval.decisionDiagnostics());
        details.put("stageLatencies", retrieval.stageLatencies());
        details.put("candidates", redactMaps(retrieval.candidates(), redactedTypes));
        return details;
    }

    private String structuredKnowledgeFallback(
            RagRetrievalService.RetrievalResult retrieval) {
        if (retrieval == null || !retrieval.answerable() || !hasText(retrieval.context())
                || retrieval.citations() == null || retrieval.citations().isEmpty()) {
            return null;
        }
        Matcher matcher = STRUCTURED_ANSWER_IN_CONTEXT.matcher(retrieval.context());
        Map<String, String> uniqueAnswers = new LinkedHashMap<>();
        while (matcher.find()) {
            String answer = matcher.group(1).strip();
            if (!hasText(answer)) continue;
            String normalized = normalizeQuestionForMatching(answer);
            if (hasText(normalized)) uniqueAnswers.putIfAbsent(normalized, answer);
        }
        return uniqueAnswers.size() == 1
            ? uniqueAnswers.values().iterator().next() : null;
    }

    private void saveReplyLog(BotMessage aiMessage, String question,
                               RagRetrievalService.RetrievalResult retrieval,
                               String reply, boolean directKnowledge, ChatResponse aiResponse,
                               String source, String answerStatus, AnswerDecision answerDecision,
                               String fallbackDecision,
                               NlpIntentClassifier.IntentAnalysis nlpIntent,
                               List<Map<String, Object>> citations,
                               Set<String> redactedTypes,
                               String modelPrompt,
                               String promptVersion,
                               List<PromptTrace> promptTraces,
                               List<String> modelProtocolViolations,
                               List<ModelProtocolViolation> modelProtocolViolationDetails,
                               List<ChatResponse> modelResponses,
                               String primaryRetrievalQuery,
                               String retrievalQuery,
                               List<String> retrievalVariants,
                               String contextResolvedQuery,
                               boolean contextResolutionApplied,
                               boolean contextDependent,
                               boolean queryRewritten,
                               boolean retrievalContextUsed,
                               boolean retrievalHistoryUsed,
                               Map<String, Object> stageLatencies,
                               long latencyMs) {
        BotAiReplyLog aiLog = new BotAiReplyLog();
        aiLog.setMessageId(aiMessage.getId());
        aiLog.setPrompt(hasText(modelPrompt) ? modelPrompt : question);
        aiLog.setReply(reply);
        boolean ragPromptInvoked = promptTraces != null && promptTraces.stream()
            .anyMatch(trace -> trace.applied() && "rag".equals(trace.path()));
        aiLog.setRagUsed(ragPromptInvoked || "faq".equals(source)
            || "knowledge_qa".equals(source) || "rag_ai".equals(source)
            || "rag_guardrail".equals(source));
        aiLog.setPurpose("CHAT");
        aiLog.setLatencyMs((int) Math.min(Integer.MAX_VALUE, latencyMs));
        aiLog.setCitedChunkIds(citations.stream()
            .filter(citation -> "document".equals(citation.get("sourceType"))
                || "image".equals(citation.get("sourceType")))
            .map(citation -> Objects.toString(citation.get("sourceId"), ""))
            .filter(value -> !value.isBlank())
            .collect(Collectors.joining(",")));
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("source", source);
        trace.put("answerStatus", answerStatus);
        trace.put("answerDecision", answerDecision.name());
        trace.put("fallbackDecision", fallbackDecision);
        trace.put("promptVersion", promptVersion);
        addPromptTraceDetails(trace, promptTraces);
        trace.put("modelProtocolViolations", modelProtocolViolations == null
            ? Collections.emptyList() : List.copyOf(modelProtocolViolations));
        trace.put("modelProtocolViolationDetails",
            protocolViolationDetails(modelProtocolViolationDetails));
        addModelInvocationDetails(trace, modelResponses);
        trace.put("nlpIntent", nlpIntentDetails(nlpIntent));
        trace.put("confidence", retrieval.confidence());
        trace.put("decision", retrieval.decision());
        trace.put("rerankDiagnostics", retrieval.rerankDiagnostics());
        trace.put("stageLatencies", stageLatencies == null
            ? Collections.emptyMap() : stageLatencies);
        trace.put("originalQuery", question);
        trace.put("primaryRetrievalQuery", primaryRetrievalQuery);
        trace.put("retrievalQuery", retrievalQuery);
        trace.put("retrievalVariants", retrievalVariants == null
            ? Collections.emptyList() : retrievalVariants);
        trace.put("contextResolvedQuery", contextResolvedQuery);
        trace.put("contextResolutionApplied", contextResolutionApplied);
        trace.put("contextDependent", contextDependent);
        trace.put("queryRewritten", queryRewritten);
        trace.put("retrievalContextUsed", retrievalContextUsed);
        trace.put("retrievalHistoryUsed", retrievalHistoryUsed);
        trace.put("citations", citations);
        trace.put("candidates", redactMaps(retrieval.candidates(), redactedTypes));
        aiLog.setTraceJson(toJson(trace));

        if (aiResponse != null) {
            aiLog.setSuccess(aiResponse.isSuccess() ? 1 : 0);
            aiLog.setModelName(aiResponse.getModel());
            aiLog.setProviderCode(aiResponse.getProviderCode());
            aiLog.setTokensInput(totalInputTokens(modelResponses));
            aiLog.setTokensOutput(totalOutputTokens(modelResponses));
            aiLog.setCallStatus(aiResponse.isSuccess() ? "SUCCESS" : "FAILED");
            aiLog.setCostCents(totalEstimatedCost(modelResponses));
        } else {
            aiLog.setSuccess("error".equals(answerStatus) ? 0 : 1);
            aiLog.setCallStatus(directKnowledge ? "SUCCESS" : "SKIPPED");
            aiLog.setCostCents(0);
        }
        aiReplyLogMapper.insert(aiLog);
    }

    private int estimateCost(ChatResponse response) {
        if ("deepseek".equals(response.getProviderCode())) {
            return (response.getInputTokens() * 5 + response.getOutputTokens() * 15) / 10000;
        }
        return (response.getInputTokens() * 15 + response.getOutputTokens() * 60) / 10000;
    }

    private BotMessage saveMessage(Long conversationId, String role, String content, String metadata) {
        return saveMessage(conversationId, role, content, metadata, "text");
    }

    private BotMessage saveMessage(Long conversationId, String role, String content,
                                   String metadata, String contentType) {
        BotMessage message = new BotMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContentType(contentType);
        message.setContent(content);
        message.setMetadata(metadata);
        messageService.save(message);
        return message;
    }

    private String buildPrompt(String chatHistory, String ragContext, String userQuestion,
                               String retrievalQuery, boolean partialEvidence) {
        StringBuilder prompt = new StringBuilder("用户问题：").append(userQuestion)
            .append(!Objects.equals(userQuestion, retrievalQuery)
                ? "\n归一化或补全后的本轮意图：" + retrievalQuery : "")
            .append("\n回答目标：以用户问题中明确点名的产品、业务或对象为唯一主体，首句直接回答该主体是什么、做什么或如何处理。")
            .append("补全后的本轮意图只用于消除省略和指代，回答仍需自然承接用户原话。")
            .append("如果内部事实同时介绍公司或多个产品，只抽取能回答当前主体和意图的事实，不复述无关内容。")
            .append("不得把会员类型、账号类型、企业认证、电子合同使用资格等不同概念相互推导。")
            .append("对于登录、注册、认证、签署等流程问题，可以组合内部事实中明确存在的前置步骤和后续步骤。")
            .append("如果已能确认主要流程但无法确认具体入口或按钮，应先回答已确认的步骤，再询问用户使用端，")
            .append("不得因为缺少局部操作细节而把整个问题判定为无法回答。")
            .append("对话历史中的客服回答不属于已核实事实；如果历史回答与本轮内部事实冲突，")
            .append("必须以内部事实为准并明确更正之前的说法，禁止无说明地改口。")
            .append("允许把内部事实中明确存在的产品定位和用途归纳成简洁答案，但不得扩展事实未提供的能力。")
            .append("对于优势、特点、介绍或比较类问题，只要内部事实能支持具体能力或适用场景，就应归纳作答；")
            .append("不要因为客户问法与事实标题不完全一致而拒答。比较时先客观说明自身特点，不评价未提供事实的其他平台。")
            .append("输出前检查语句是否完整通顺，避免错别字、残缺词语和生硬拼接。")
            .append("\n证据冲突处理：").append(EVIDENCE_CONFLICT_INSTRUCTION)
            .append("\n回答约束：只有企业内部事实明确说明了该问题的产品定位、用途、功能、规则或条件时才能回答。")
            .append("资料没有提及不等于不支持，禁止据此推断。若事实能够回答核心问题但缺少局部条件，")
            .append("第一行输出 ").append(PARTIAL_ANSWER_SIGNAL)
            .append("，随后先回答已确认内容，再提出一个针对缺失条件的问题；")
            .append("完全没有相关依据时才只输出 ").append(NO_ANSWER_SIGNAL).append("。")
            .append("\n输出格式：").append(PLAIN_TEXT_OUTPUT_INSTRUCTION)
            .append("\n回答结构：").append(ADAPTIVE_REPLY_STRUCTURE_INSTRUCTION)
            .append("\n作答前最后检查：逐句核对与当前问题直接相关的事实。若结论后还提供了必要动作、入口、")
            .append("替代路径或并列能力，必须一并回答，不能在首句结论后提前结束。");
        if (partialEvidence) {
            prompt.append("\n当前只能确认通用能力，不能确认客户点名的具体对象。必须使用 ")
                .append(PARTIAL_ANSWER_SIGNAL)
                .append("，说明已确认边界并追问具体签署主体或使用场景，不得直接宣称该对象受支持。");
        }
        int usedTokens = estimateTokens(prompt.toString());

        if (hasText(ragContext)) {
            int remainingTokenBudget = Math.max(0, maxPromptTokens - usedTokens);
            int allowedChars = Math.min(remainingTokenBudget * CHARS_PER_TOKEN,
                Math.max(0, maxPromptTokens));
            if (allowedChars > 50) {
                String evidence = ragContext.substring(0,
                    Math.min(allowedChars, ragContext.length()));
                boolean truncated = evidence.length() < ragContext.length();
                String inserted = evidence + (truncated ? "\n...(知识库内容已截断)" : "") + "\n";
                prompt.insert(0, inserted);
                usedTokens += estimateTokens(inserted);
            }
        }

        if (hasText(chatHistory)) {
            int allowedChars = Math.max(0, (maxPromptTokens - usedTokens) * CHARS_PER_TOKEN);
            if (allowedChars > 30) {
                prompt.insert(0, chatHistory.substring(0, Math.min(allowedChars, chatHistory.length())) + "\n");
            }
        }
        return prompt.toString();
    }

    private String buildNativeFallbackPrompt(String chatHistory, String userQuestion) {
        StringBuilder prompt = new StringBuilder("用户问题：").append(userQuestion)
            .append("\n请先判断问题是否属于电子合同、电子签名、签署合规或合同管理业务范围。")
            .append("不属于该范围时只输出 ").append(NO_ANSWER_SIGNAL).append("。")
            .append("属于该范围且可以仅依据稳定通用知识完整回答时直接作答；")
            .append("若只能回答通用部分而企业具体事实仍缺失，第一行输出 ")
            .append(PARTIAL_ANSWER_SIGNAL)
            .append("，随后先回答通用部分，再提出一个针对性问题。")
            .append("若问题完全依赖公司、产品、价格、合同、交付、售后、账户、隐私或合规的具体事实，")
            .append("只输出 ").append(NO_ANSWER_SIGNAL).append("，不要猜测。")
            .append("\n输出格式：").append(PLAIN_TEXT_OUTPUT_INSTRUCTION)
            .append("\n回答结构：").append(ADAPTIVE_REPLY_STRUCTURE_INSTRUCTION);
        if (hasText(chatHistory)) {
            int allowedChars = Math.max(0, (maxPromptTokens - estimateTokens(prompt.toString()))
                * CHARS_PER_TOKEN);
            if (allowedChars > 30) {
                prompt.insert(0, chatHistory.substring(0,
                    Math.min(allowedChars, chatHistory.length())) + "\n");
            }
        }
        return prompt.toString();
    }

    private String buildChatHistory(List<BotMessage> messages, Set<String> redactedTypes) {
        if (messages == null || messages.size() <= 1) return null;
        int end = messages.size() - 1;
        int start = Math.max(0, end - Math.max(0, maxHistoryMessages));
        if (start >= end) return null;

        StringBuilder history = new StringBuilder("【对话历史】\n");
        for (int i = start; i < end; i++) {
            BotMessage message = messages.get(i);
            history.append("user".equals(message.getRole()) ? "用户" : "客服")
                .append(": ").append(redact(message.getContent(), redactedTypes)).append("\n");
        }
        return history.toString();
    }

    private String buildRetrievalHistory(List<BotMessage> messages, boolean contextDependent,
                                         Set<String> redactedTypes) {
        if (!contextDependent || messages == null || messages.size() <= 1) return null;
        int end = messages.size() - 1;
        int start = Math.max(0, end - Math.max(0, maxRetrievalHistoryMessages));
        StringBuilder history = new StringBuilder();
        for (int i = start; i < end; i++) {
            BotMessage message = messages.get(i);
            if (!"user".equals(message.getRole()) && !"ai".equals(message.getRole())) continue;
            String content = message.getContent();
            if (!hasText(content)) continue;
            history.append("user".equals(message.getRole()) ? "用户: " : "客服: ")
                .append(redact(content, redactedTypes)).append('\n');
        }
        if (history.isEmpty()) return null;
        int historyChars = Math.max(0, maxRetrievalHistoryChars);
        if (historyChars == 0) return null;
        return history.length() <= historyChars
            ? history.toString().strip()
            : history.substring(history.length() - historyChars).strip();
    }

    private boolean isParentCompanyQuestion(String question) {
        if (!hasText(question)) return false;
        String normalized = normalizeQuestionForMatching(question);
        boolean pointProductContext = containsAny(normalized,
            "点签", "电子合同", "电子签约");
        boolean explicitOtherProduct = containsAny(normalized,
            "ca锁", "实体锁", "ukey", "安全控件", "守信签", "总部电子签章",
            "翔晟电子签章", "翔晟");
        if (pointProductContext && !explicitOtherProduct) return false;
        return containsConfiguredKeyword(question, outOfScopeKeywords)
            || hasStandaloneCa(question)
            || containsAny(normalized, "翔晟");
    }

    private boolean isClearlyUnrelatedQuestion(String question) {
        if (!hasText(question)) return false;
        String normalized = question.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s，。！？?、；;：:（）()【】\\[\\]“”\"']+", "");
        if (BUSINESS_SCOPE_TERMS.stream().anyMatch(normalized::contains)) return false;
        return CLEARLY_UNRELATED_TERMS.stream().anyMatch(normalized::contains);
    }

    private BasicConversationIntent matchBasicConversationIntent(String question) {
        if (!hasText(question)) return null;
        String normalized = Normalizer.normalize(question, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{P}\\s]+", "")
            .replaceFirst("^(?:请问|麻烦问一下|想问一下|我想问一下)+", "")
            .replaceFirst("(?:呀|啊|呢|哦|噢|呐|嘛|吧|啦|哈)+$", "");
        if (BASIC_IDENTITY_QUESTIONS.contains(normalized)) {
            return BasicConversationIntent.IDENTITY;
        }
        if (BASIC_CAPABILITY_QUESTIONS.contains(normalized)) {
            return BasicConversationIntent.CAPABILITY;
        }
        if (normalized.matches(
                "^(?:你|您)(?:都)?(?:能|可以|会)(?:帮(?:我|我们|客户|用户))?"
                    + "(?:处理|解决|回答)(?:哪些|什么)(?:事|事情|问题|业务)$")) {
            return BasicConversationIntent.CAPABILITY;
        }
        if (BASIC_GREETINGS.contains(normalized)) {
            return BasicConversationIntent.GREETING;
        }
        if (BASIC_THANKS.contains(normalized)) {
            return BasicConversationIntent.THANKS;
        }
        if (BASIC_GOODBYES.contains(normalized)) {
            return BasicConversationIntent.GOODBYE;
        }
        return null;
    }

    private String stripLeadingCourtesyPrefix(String question) {
        if (!hasText(question)) return question;
        String stripped = question.strip().replaceFirst(
            "(?i)^(?:(?:你好|您好|hello|hi|嗨|哈喽)[，,。.!！?？、\\s]*|"
                + "(?:请问|麻烦问一下|想问一下|咨询一下)[，,。.!！?？、\\s]*)+", "");
        return hasText(stripped) ? stripped : question.strip();
    }

    private NativeFallbackDecision nativeFallbackDecision(
            String question, NlpIntentClassifier.IntentAnalysis nlpIntent) {
        if (!nativeFallbackEnabled) return NativeFallbackDecision.DISABLED;
        if (isExplicitlyUnrelatedNativeRequest(question)) {
            return NativeFallbackDecision.OUT_OF_SCOPE;
        }
        if (nlpIntent.intentCode() == NlpIntentClassifier.IntentCode.CONTRACT_DRAFTING) {
            return NativeFallbackDecision.CONTRACT_DRAFTING_CLARIFY;
        }
        if (nlpIntent.intentCode() == NlpIntentClassifier.IntentCode.CONTRACT_TYPE_CAPABILITY) {
            return NativeFallbackDecision.CONTRACT_CAPABILITY_NO_EVIDENCE;
        }
        if (nlpIntent.intentCode() == NlpIntentClassifier.IntentCode.CONTRACT_LEGAL_RISK) {
            return NativeFallbackDecision.CONTRACT_LEGAL_RISK;
        }
        if (containsConfiguredKeyword(question, nativeFallbackHighRiskKeywords)) {
            return NativeFallbackDecision.HIGH_RISK;
        }
        if (isAmbiguousClarification(question)) {
            return NativeFallbackDecision.CLARIFY;
        }
        if (!isNativeFallbackBusinessQuestion(question, nlpIntent)) {
            return NativeFallbackDecision.OUT_OF_SCOPE;
        }
        return NativeFallbackDecision.NATIVE;
    }

    private boolean isNativeFallbackBusinessQuestion(
            String question, NlpIntentClassifier.IntentAnalysis nlpIntent) {
        if (nlpIntent.intentCode() != NlpIntentClassifier.IntentCode.UNKNOWN
                || isCompanyIntroductionQuestion(question)) {
            return true;
        }
        String normalized = normalizeQuestionForMatching(question);
        return NATIVE_FALLBACK_DOMAIN_ANCHORS.stream().anyMatch(normalized::contains);
    }

    private boolean isExplicitlyUnrelatedNativeRequest(String question) {
        return hasText(question)
            && NATIVE_FALLBACK_UNRELATED_REQUEST.matcher(question).matches();
    }

    private NativeFallbackResponse nativeFallbackResponse(
            String question, NlpIntentClassifier.IntentAnalysis nlpIntent,
            String chatHistory,
            EmotionService.EmotionResult emotion, Long preferredModelId) {
        NativeFallbackDecision decision = nativeFallbackDecision(question, nlpIntent);
        String decisionName = decision.name().toLowerCase(Locale.ROOT);
        if (decision == NativeFallbackDecision.CONTRACT_DRAFTING_CLARIFY) {
            String subject = hasText(nlpIntent.subject()) ? nlpIntent.subject() : "合同";
            return new NativeFallbackResponse(
                CONTRACT_DRAFTING_CLARIFICATION_REPLY.formatted(subject, subject),
                "clarify", "clarify", "clarify", decisionName,
                AnswerDecision.CLARIFY, false, true, null, null);
        }
        if (decision == NativeFallbackDecision.CONTRACT_CAPABILITY_NO_EVIDENCE) {
            String subject = hasText(nlpIntent.subject())
                ? nlpIntent.subject() : "该合同类型";
            return new NativeFallbackResponse(
                CONTRACT_CAPABILITY_NO_EVIDENCE_REPLY.formatted(subject),
                "no_answer", "no_answer", "restricted", decisionName,
                AnswerDecision.NO_KNOWLEDGE, false, true, null, null);
        }
        if (decision == NativeFallbackDecision.CONTRACT_LEGAL_RISK) {
            return new NativeFallbackResponse(
                CONTRACT_LEGAL_RISK_REPLY,
                "no_answer", "no_answer", "restricted", decisionName,
                AnswerDecision.HANDOFF, true, true, null, null);
        }
        if (decision == NativeFallbackDecision.CLARIFY) {
            return new NativeFallbackResponse(
                configuredReply(nativeFallbackClarificationReply,
                    DEFAULT_NATIVE_FALLBACK_CLARIFICATION_REPLY),
                "clarify", "clarify", "clarify", decisionName,
                AnswerDecision.CLARIFY, false, false, null, null);
        }
        if (decision == NativeFallbackDecision.HIGH_RISK) {
            return new NativeFallbackResponse(
                configuredReply(nativeFallbackHighRiskReply,
                    DEFAULT_NATIVE_FALLBACK_HIGH_RISK_REPLY),
                "no_answer", "no_answer", "restricted", decisionName,
                AnswerDecision.HANDOFF, true, true, null, null);
        }
        if (decision == NativeFallbackDecision.OUT_OF_SCOPE) {
            return new NativeFallbackResponse(
                configuredReply(unrelatedReply, noAnswerReply),
                "out_of_scope", "out_of_scope", "restricted", decisionName,
                AnswerDecision.NO_KNOWLEDGE, false, false, null, null);
        }
        if (decision == NativeFallbackDecision.DISABLED) {
            return new NativeFallbackResponse(noAnswerReply,
                "no_answer", "no_answer", "restricted", decisionName,
                AnswerDecision.NO_KNOWLEDGE, false, true, null, null);
        }

        String fallbackPrompt = buildNativeFallbackPrompt(chatHistory, question);
        SystemPromptResolution systemPrompt = nativeFallbackSystemPromptFor(emotion);
        ChatResponse response = aiModelService.chatWithModel(
            fallbackPrompt, systemPrompt.content(), preferredModelId);
        ModelAnswerSignalParser.ParsedAnswer parsedAnswer =
            MODEL_ANSWER_SIGNAL_PARSER.parse(response.getContent());
        if (response.isSuccess() && parsedAnswer.isAnswer()) {
            boolean partialAnswer = parsedAnswer.isPartial();
            return new NativeFallbackResponse(parsedAnswer.content(),
                "native_ai", "answered", partialAnswer ? "partial" : "native", decisionName,
                partialAnswer ? AnswerDecision.ANSWER_PARTIAL : AnswerDecision.ANSWER,
                false, true, response, fallbackPrompt, parsedAnswer.violation(),
                systemPrompt.trace());
        }
        return new NativeFallbackResponse(noAnswerReply,
            response.isSuccess() ? "no_answer" : "error",
            response.isSuccess() ? "no_answer" : "error", "restricted", decisionName,
            AnswerDecision.NO_KNOWLEDGE, false, true, response, fallbackPrompt,
            parsedAnswer.violation(), systemPrompt.trace());
    }

    private boolean isAmbiguousClarification(String question) {
        if (!hasText(question)) return false;
        String normalized = question.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s，。！？、；：,.!?]", "");
        return normalized.length() <= 18
            && containsConfiguredKeyword(normalized, nativeFallbackClarificationKeywords);
    }

    private boolean containsConfiguredKeyword(String question, String configuredKeywords) {
        if (!hasText(question) || !hasText(configuredKeywords)) return false;
        String normalized = question.toLowerCase(Locale.ROOT);
        for (String configured : configuredKeywords.split("[,，\\n]")) {
            String keyword = configured.trim().toLowerCase(Locale.ROOT);
            if (!keyword.isEmpty() && normalized.contains(keyword)) return true;
        }
        return false;
    }

    private int estimateTokens(String text) {
        return text == null ? 0 : text.length() / CHARS_PER_TOKEN;
    }

    private String stripCitationPresentation(String reply, List<Map<String, Object>> citations) {
        if (!hasText(reply)) return reply;
        String cleaned = reply;
        int footer = cleaned.indexOf("参考来源：");
        if (footer >= 0) cleaned = cleaned.substring(0, footer);
        if (citations != null) {
            for (int ref = 1; ref <= citations.size(); ref++) {
                cleaned = cleaned.replaceAll(
                    "\\s*(?:\\[" + ref + "\\]|【" + ref + "】)", "");
            }
        }
        return cleaned.replaceAll("[ \\t]{2,}", " ").strip();
    }

    private HandoffCoordinator.HandoffResult coordinateHandoff(
            Long conversationId, String reason, String priority) {
        try {
            return handoffCoordinator.handoff(conversationId, reason, priority);
        } catch (Exception e) {
            log.error("Could not create handoff ticket for conversation {}", conversationId, e);
            return HandoffCoordinator.HandoffResult.failed("转人工工单创建失败");
        }
    }

    private String transferReason(boolean outputBlocked, String answerStatus,
                                  boolean lowConfidence, boolean highRiskNoKnowledge,
                                  EmotionService.EmotionResult emotion) {
        String reason = null;
        if (outputBlocked) reason = "AI 回答触发安全规则";
        else if (highRiskNoKnowledge) reason = "高风险业务问题缺少可核实依据";
        else if ("evidence_conflict".equals(answerStatus)) reason = "可核实事实口径冲突";
        else if ("insufficient_evidence".equals(answerStatus)) reason = "回答包含无依据的事实清单";
        else if ("no_answer".equals(answerStatus)) reason = "现有知识无法回答";
        else if ("error".equals(answerStatus)) reason = "AI 模型调用失败";
        else if (lowConfidence) reason = "AI 回答置信度低于阈值";
        return appendEmotionReason(reason, emotion);
    }

    private String appendEmotionReason(String reason, EmotionService.EmotionResult emotion) {
        if (emotion == null || !emotion.shouldHandoff()) {
            return hasText(reason) ? reason : "需要人工客服进一步处理";
        }
        String emotionReason = "用户情绪高风险（" + emotion.displayLabel()
            + "，连续负面 " + emotion.negativeStreak() + " 轮）";
        return hasText(reason) ? reason + "；" + emotionReason : emotionReason;
    }

    private String transferPriority(String basePriority,
                                    EmotionService.EmotionResult emotion) {
        if (emotion == null || !emotion.shouldHandoff()) return basePriority;
        return priorityRank(emotion.priority()) < priorityRank(basePriority)
            ? emotion.priority() : basePriority;
    }

    private int priorityRank(String priority) {
        if (priority == null) return 3;
        return switch (priority) {
            case "P0" -> 0;
            case "P1" -> 1;
            case "P2" -> 2;
            default -> 3;
        };
    }

    private boolean isPriceQuestion(String question) {
        if (!hasText(question)) return false;
        String normalized = question.toLowerCase(Locale.ROOT);
        if (containsConfiguredKeyword(question, priceHandoffKeywords)) return true;
        boolean hasPriceTerm = PRICE_TERMS.stream().anyMatch(normalized::contains);
        if (!hasPriceTerm) return false;
        boolean customQuoteContext = CUSTOM_QUOTE_CONTEXT_TERMS.stream()
            .anyMatch(normalized::contains);
        boolean explicitQuantity = normalized.matches(".*\\d+\\s*(人|份|套|单|次|年).*?")
            || normalized.matches(".*(几十|几百|几千|多少)(人|份|套|单|次).*?");
        return customQuoteContext || explicitQuantity;
    }

    private boolean isUnifiedContractPricingQuestion(
            String question, List<BotMessage> recentMessages) {
        if (!hasText(question)) return false;
        String normalized = normalizeQuestionForMatching(question);
        boolean hasPriceTerm = PRICE_TERMS.stream().anyMatch(normalized::contains)
            || containsConfiguredKeyword(question, priceHandoffKeywords);
        if (!hasPriceTerm) return false;

        boolean contractContext = containsAny(normalized,
            "点签", "电子合同", "电子签约", "合同", "套餐", "签署量", "年用量",
            "份", "每份", "一份", "每单", "每次");
        if (contractContext) return true;

        String previousQuestion = previousUserQuestion(recentMessages, question);
        String latestReply = latestAiReply(recentMessages);
        boolean previousPricingContext = hasText(previousQuestion)
            && containsAny(previousQuestion,
                "点签", "电子合同", "电子签约", "合同", "套餐", "签署量");
        boolean latestPricingReply = hasText(latestReply)
            && containsAny(latestReply,
                "合同套餐", "每份合同", "价格", "收费", "官网标准套餐");
        return previousPricingContext || latestPricingReply;
    }

    private boolean isOfficialWebsiteQuestion(
            String question, List<BotMessage> recentMessages) {
        if (!hasText(question)) return false;
        String normalized = normalizeQuestionForMatching(question);
        boolean websiteTerm = containsAny(normalized,
            "官网", "官方网站", "网址", "网站地址", "网页地址", "官网链接", "官网地址");
        if (!websiteTerm) return false;
        if (containsAny(normalized, "点签", "电子合同", "电子签约")) return true;

        String previousQuestion = previousUserQuestion(recentMessages, question);
        return hasText(previousQuestion)
            && containsAny(normalizeQuestionForMatching(previousQuestion),
                "点签", "电子合同", "电子签约");
    }

    private boolean isOtherProductClarificationQuestion(String question) {
        if (!hasText(question)) return false;
        String normalized = normalizeQuestionForMatching(question);
        boolean dianqian = containsAny(normalized, "点签", "电子合同", "电子签约");
        boolean xiangsheng = containsAny(normalized, "翔晟");
        boolean directOtherProduct = containsAny(normalized,
            "ca锁", "实体锁", "安全控件", "守信签", "总部电子签章", "翔晟电子签章");
        if (hasStandaloneCa(question)) {
            return !dianqian && !xiangsheng && !directOtherProduct;
        }
        boolean ukey = containsAny(normalized, "ukey", "u-key");
        return ukey && !dianqian && !xiangsheng;
    }

    private boolean isUKeyDianqianQuestion(String question) {
        if (!hasText(question)) return false;
        String normalized = normalizeQuestionForMatching(question);
        return containsAny(normalized, "ukey", "u-key")
            && containsAny(normalized, "点签", "电子合同", "电子签约")
            && !containsAny(normalized, "翔晟");
    }

    private boolean hasStandaloneCa(String question) {
        return hasText(question) && question.matches("(?is).*(?<![a-z])ca(?![a-z]).*");
    }

    private boolean isPriceQualificationQuestion(String question) {
        if (!hasText(question)) return false;
        String normalized = normalizeQuestionForMatching(question);
        boolean hasPriceTerm = PRICE_TERMS.stream().anyMatch(normalized::contains);
        if (!hasPriceTerm) return false;

        boolean productPricingContext = containsAny(normalized,
            "点签", "电子合同", "电子签约", "你们", "贵司", "平台", "套餐", "签署量", "年用量")
            || List.of("怎么收费", "如何收费", "怎样收费", "多少钱", "价格多少",
                "收费标准", "价格怎么样").contains(normalized);
        if (!productPricingContext) return false;
        // Specific package or membership prices should be answered from the
        // knowledge base (or routed as a custom quote), not treated as a
        // request for annual-volume qualification.
        if (SPECIFIC_PRICE_CONTEXT_TERMS.stream().anyMatch(normalized::contains)) return false;

        PriceVolumeBand volumeBand = classifyAnnualSigningVolume(question, false);
        return volumeBand != PriceVolumeBand.UNKNOWN
            || !containsConfiguredKeyword(question, priceHandoffKeywords);
    }

    private boolean isAwaitingAnnualSigningVolume(
            List<BotMessage> recentMessages, String currentQuestion) {
        String reply = latestAiReply(recentMessages);
        return hasText(reply)
            && reply.contains(PRICE_VOLUME_QUESTION)
            && classifyAnnualSigningVolume(currentQuestion, true) != PriceVolumeBand.UNKNOWN;
    }

    private boolean isPriceHandoffConsent(
            String text, List<BotMessage> recentMessages) {
        if (!latestAiReplyOffersPriceHandoff(recentMessages) || !hasText(text)) return false;
        String normalized = normalizeQuestionForMatching(text);
        return Set.of(
            "需要", "需要的", "要", "可以", "可以的", "好", "好的", "是", "是的",
            "麻烦了", "帮我转", "转吧", "请帮我转").contains(normalized)
            || isExplicitHandoffRequest(text);
    }

    private boolean isPriceHandoffDeclined(
            String text, List<BotMessage> recentMessages) {
        if (!latestAiReplyOffersPriceHandoff(recentMessages) || !hasText(text)) return false;
        String normalized = normalizeQuestionForMatching(text);
        return Set.of(
            "不需要", "不用", "不用了", "暂时不用", "先不用", "不转", "不转人工",
            "暂时不需要", "先不需要").contains(normalized);
    }

    private boolean latestAiReplyOffersPriceHandoff(List<BotMessage> recentMessages) {
        String reply = latestAiReply(recentMessages);
        return hasText(reply) && reply.contains("需要我帮您转人工吗");
    }

    private String latestAiReply(List<BotMessage> recentMessages) {
        if (recentMessages == null) return null;
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            BotMessage message = recentMessages.get(i);
            if (message != null && "ai".equals(message.getRole())
                    && hasText(message.getContent())) {
                return message.getContent();
            }
        }
        return null;
    }

    private PriceVolumeBand classifyAnnualSigningVolume(String text, boolean awaitingVolume) {
        if (!hasText(text)) return PriceVolumeBand.UNKNOWN;
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replace("一千", "1000")
            .replaceAll("[,，\\s]", "");

        if (normalized.matches(".*(?:(?:不到|少于|低于|小于|不满)1000|1000(?:以下|以内|之内)).*")) {
            return PriceVolumeBand.STANDARD;
        }
        if (normalized.matches(".*(?:(?:不少于|不低于|大于等于|达到|超过|高于|大于)1000|1000(?:以上|及以上|起)).*")) {
            return PriceVolumeBand.ENTERPRISE;
        }
        if (normalized.contains("几百")) return PriceVolumeBand.STANDARD;
        if (normalized.contains("几千") || normalized.contains("上千")) {
            return PriceVolumeBand.ENTERPRISE;
        }

        Matcher matcher = ANNUAL_SIGNING_VOLUME_NUMBER.matcher(normalized);
        while (matcher.find()) {
            int start = Math.max(0, matcher.start() - 10);
            int end = Math.min(normalized.length(), matcher.end() + 10);
            String context = normalized.substring(start, end);
            String suffix = normalized.substring(matcher.end(), end);
            if (suffix.startsWith("元") || suffix.startsWith("块") || suffix.startsWith("¥")) {
                continue;
            }
            boolean volumeContext = awaitingVolume || containsAny(context,
                "份", "单", "次", "合同", "签署量", "年用量", "每年", "一年", "/年");
            if (!volumeContext) continue;
            try {
                long volume = Long.parseLong(matcher.group());
                return volume < 1000 ? PriceVolumeBand.STANDARD : PriceVolumeBand.ENTERPRISE;
            } catch (NumberFormatException ignored) {
                return PriceVolumeBand.UNKNOWN;
            }
        }
        return PriceVolumeBand.UNKNOWN;
    }

    private boolean isHumanHandling(BotConversation conversation) {
        return "transferred".equals(conversation.getStatus())
            && "PROCESSING".equals(conversation.getHandoffStatus())
            && conversation.getAssignedAgentId() != null;
    }

    private boolean isWaitingForHuman(BotConversation conversation) {
        return "transferred".equals(conversation.getStatus()) && !isHumanHandling(conversation);
    }

    private boolean isPreviousQuestionRequest(String text) {
        if (!hasText(text)) return false;
        String normalized = text.replaceAll("[\\s，。！？?、；;：:（）()【】\\[\\]“”\"']+", "");
        return normalized.contains("刚才问") || normalized.contains("刚才的问题")
            || normalized.contains("我问了什么") || normalized.contains("问的什么")
            || ((normalized.contains("上面问") || normalized.contains("前面问"))
                && (normalized.contains("什么") || normalized.contains("哪个")))
            || ((normalized.contains("上一个问题") || normalized.contains("前一个问题")
                || normalized.contains("上一条问题"))
                && (normalized.contains("什么") || normalized.contains("哪个")
                    || normalized.contains("内容")));
    }

    private boolean isHandoffExplanationRequest(String text) {
        if (!hasText(text)) return false;
        String normalized = text.replaceAll("\\s+", "");
        return normalized.contains("怎么回事") || normalized.contains("为什么转人工")
            || normalized.contains("为何转人工") || normalized.contains("为什么要转人工")
            || normalized.contains("怎么还没") || normalized.contains("人工什么时候")
            || normalized.contains("客服什么时候") || normalized.contains("还要多久")
            || normalized.contains("处理到哪");
    }

    private boolean isHandoffCancellation(String text) {
        if (!hasText(text)) return false;
        String normalized = text.replaceAll("\\s+", "");
        return normalized.contains("算了") || normalized.contains("不用了")
            || normalized.contains("不需要了") || normalized.contains("不等了")
            || normalized.contains("取消转人工") || normalized.contains("取消人工")
            || normalized.contains("不用转人工") || normalized.contains("不用客服");
    }

    private boolean isExplicitHandoffRequest(String text) {
        if (!hasText(text) || isHandoffCancellation(text)) return false;
        String normalized = text.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s，。！？?、；;：:（）()【】\\[\\]“”\"'！]+", "");
        if (normalized.isBlank()) return false;

        // Explanations and status questions must continue through the waiting
        // handoff flow instead of creating a new ticket.
        if (List.of(
                "为什么转人工", "为何转人工", "怎么回事", "人工什么时候",
                "客服什么时候", "还要多久", "是否已经转人工", "有没有转人工",
                "转人工是什么意思", "怎么转人工").stream()
                .anyMatch(normalized::contains)) {
            return false;
        }

        if (List.of("转人工", "人工客服", "找人工", "找客服", "联系客服",
                "人工介入", "人工处理").contains(normalized)) {
            return true;
        }
        if (normalized.startsWith("转人工")) return true;
        return normalized.matches(".*(?:请|帮我|我要|我想|需要|申请|请求|麻烦|可以|能否|能不能)"
            + ".*(?:转人工|人工客服|找人工|找客服|联系客服|人工介入|人工处理).*");
    }

    private boolean looksLikeHandoffSupplement(String text) {
        if (!hasText(text)) return false;
        String candidate = handoffSupplementCandidate(text);
        if (!hasText(candidate) || candidate.contains("?") || candidate.contains("？")) {
            return false;
        }
        String normalized = candidate.replaceAll("\\s+", "");
        // A media OCR block can contain labels such as "手机号". Only use the
        // customer's caption for this branch, and require an explicit value.
        if (containsConfiguredKeyword(normalized,
                "怎么,如何,为什么,为何,哪里,是否,能不能,能否,可以吗,操作,步骤,怎么办,怎么弄,请问")) {
            return false;
        }
        boolean explicitField = normalized.matches(".*(使用人数|人数|公司名称|公司|联系人|预算|订单号|手机号|电话|邮箱)"
            + "(是|为|：|:)\\S+.*")
            || normalized.matches(".*(手机号|电话|邮箱|订单号)"
                + "(是|为|：|:)?(\\[[^]]+]|[A-Za-z0-9@._+\\-]{4,}).*");
        boolean explicitAttachment = normalized.contains("补充")
            && containsConfiguredKeyword(normalized, "截图,附件,资料,信息");
        return explicitField || explicitAttachment;
    }

    private String handoffSupplementCandidate(String text) {
        String marker = "[客户附带问题]";
        int start = text.indexOf(marker);
        if (start < 0) {
            // OCR/ASR blocks are evidence produced by the system, not an
            // explicit customer-provided handoff field.
            return text.contains("[客户发送了一张") || text.contains("[客户发送了一条")
                ? "" : text;
        }
        start += marker.length();
        int end = text.indexOf("\n\n[客户发送", start);
        return (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
    }

    private String previousUserQuestion(List<BotMessage> messages, String currentText) {
        if (messages == null || messages.isEmpty()) return null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            BotMessage message = messages.get(index);
            if (message == null || !"user".equalsIgnoreCase(message.getRole())
                    || !hasText(message.getContent())) {
                continue;
            }
            String content = message.getContent().trim();
            if (!content.equals(currentText == null ? null : currentText.trim())) return content;
        }
        return null;
    }

    private SystemPromptResolution customerServiceSystemPrompt(
            EmotionService.EmotionResult emotion, String question,
            NlpIntentClassifier.IntentAnalysis nlpIntent, String promptVersion) {
        String basePrompt = promptProvider.promptFor(promptVersion);
        String prompt = basePrompt;
        if (isCompanyIntroductionQuestion(question)) {
            prompt += "\n" + COMPANY_INTRODUCTION_ANSWER_INSTRUCTION;
        }
        if (isContractLaunchMethodQuestion(question)) {
            prompt += "\n" + CONTRACT_LAUNCH_METHOD_INSTRUCTION;
        }
        if (nlpIntent.intentCode()
                == NlpIntentClassifier.IntentCode.CONTRACT_SIGNING_OPERATION
                && nlpIntent.needsClarification()
                && question.contains("上传")) {
            prompt += "\n" + CONTRACT_UPLOAD_AMBIGUITY_INSTRUCTION;
        }
        if (nlpIntent.intentCode()
                == NlpIntentClassifier.IntentCode.CONTRACT_TYPE_CAPABILITY
                && !nlpIntent.needsClarification()
                && hasText(nlpIntent.subject())) {
            prompt += "\n" + CONTRACT_TYPE_ALREADY_SPECIFIED_INSTRUCTION.formatted(
                nlpIntent.subject());
        }
        if (isOperationalQuestion(question)) {
            prompt += "\n" + OPERATIONAL_ANSWER_INSTRUCTION;
        }
        if (isDetailedListQuestion(question)) {
            prompt += "\n" + DETAILED_LIST_ANSWER_INSTRUCTION;
        }
        if (isServiceLevelPromiseQuestion(question)) {
            prompt += "\n" + SERVICE_LEVEL_PROMISE_INSTRUCTION;
        }
        if (isCompoundQuestion(question)) {
            prompt += "\n" + COMPOUND_ANSWER_INSTRUCTION;
        }
        if (isPolaritySensitiveQuestion(question)) {
            prompt += "\n" + POLARITY_CONSISTENCY_INSTRUCTION;
        }
        if (emotion != null && emotion.label() != EmotionService.EmotionLabel.NEUTRAL) {
            prompt += "\n当前用户情绪服务策略：" + emotion.instruction();
        }
        prompt += "\n\n强制事实与安全边界：\n" + promptProvider.mandatoryPolicy();
        PromptTrace trace = new PromptTrace(true, "rag",
            promptProvider.sourceFor(promptVersion),
            CustomerServicePromptProvider.fingerprint(basePrompt),
            CustomerServicePromptProvider.fingerprint(prompt), prompt.length(), 0);
        return new SystemPromptResolution(prompt, trace);
    }

    private boolean shouldClarifyPartialEvidence(
            NlpIntentClassifier.IntentAnalysis nlpIntent) {
        return nlpIntent == null
            || nlpIntent.intentCode()
                != NlpIntentClassifier.IntentCode.CONTRACT_TYPE_CAPABILITY
            || nlpIntent.needsClarification();
    }

    private Map<String, Object> pendingClarificationState(
            NlpIntentClassifier.IntentAnalysis nlpIntent, String reply,
            String answerStatus) {
        if (nlpIntent == null
                || nlpIntent.intentCode()
                    != NlpIntentClassifier.IntentCode.CONTRACT_TYPE_CAPABILITY
                || !"answered".equals(answerStatus)
                || !isContractTypeClarificationReply(reply)) {
            return null;
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("intentCode", NlpIntentClassifier.IntentCode.CONTRACT_TYPE_CAPABILITY.name());
        state.put("missingSlot", "contractType");
        state.put("queryTemplate", "点签 是否支持签署 {contractType}");
        state.put("expiresAfterTurns", 1);
        return state;
    }

    private boolean isContractTypeClarificationReply(String reply) {
        if (!hasText(reply) || (!reply.contains("？") && !reply.contains("?"))) return false;
        return reply.contains("合同") && List.of(
            "什么合同", "哪种合同", "哪类合同", "哪一类合同", "什么类型的合同",
            "商品房买卖合同", "二手房买卖合同")
            .stream().anyMatch(reply::contains);
    }

    private boolean isServiceLevelPromiseQuestion(String question) {
        if (!hasText(question)) return false;
        String normalized = question.replaceAll("\\s+", "");
        boolean serviceAction = normalized.contains("响应")
            || normalized.contains("解决") || normalized.contains("处理");
        boolean timeOrPromise = normalized.contains("多久") || normalized.contains("小时")
            || normalized.contains("分钟") || normalized.contains("天内")
            || normalized.contains("保证") || normalized.contains("承诺");
        return serviceAction && timeOrPromise;
    }

    private String guardedKnowledgeReply(String question, boolean directAnswer) {
        if (!hasText(question)) return null;
        String normalized = question.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s，。！？?、；;：:（）()【】\\[\\]“”\"']+", "");
        if (containsAny(normalized, "合同", "协议")
                && containsAny(normalized, "盖完章", "盖章完成", "盖章后", "签署完成", "完成签署")
                && containsAny(normalized, "生效", "有效")
                && containsAny(normalized, "一定", "立即", "即时", "自动", "是不是就", "是否就")) {
            return CONTRACT_EFFECT_GUARDRAIL_REPLY;
        }
        if (containsAny(normalized, "法院", "司法")
                && containsAny(normalized, "认可", "采信", "承认")
                && containsAny(normalized, "百分之百", "一定", "保证")) {
            return COURT_RECOGNITION_GUARDRAIL_REPLY;
        }
        if (!directAnswer && containsAny(normalized, "附件", "材料")
                && containsAny(normalized, "漏", "补充", "补传")
                && containsAny(normalized, "签完", "签署完成", "已经签署", "已签")) {
            return SIGNED_ATTACHMENT_GUARDRAIL_REPLY;
        }
        if (!directAnswer && containsAny(normalized, "验证码", "短信码")
                && containsAny(normalized, "收不到", "收不着", "没收到", "未收到")) {
            return VERIFICATION_CODE_GUARDRAIL_REPLY;
        }
        if (containsAny(normalized, "点签")
                && containsAny(normalized, "e签宝", "上上签", "法大大", "契约锁")
                && containsAny(normalized, "哪个", "更安全", "更有法律效力", "对比", "比较")) {
            return COMPETITOR_COMPARISON_GUARDRAIL_REPLY;
        }
        if (containsAny(normalized, "响应", "解决", "处理")
                && containsAny(normalized, "一小时", "1小时")
                && containsAny(normalized, "保证", "所有问题", "全部问题")) {
            return SERVICE_RESPONSE_GUARDRAIL_REPLY;
        }
        if (containsAny(normalized, "市场占有率", "市场份额")
                && containsAny(normalized, "官方证明", "官方认证", "证明", "依据")) {
            return MARKET_SHARE_EVIDENCE_GUARDRAIL_REPLY;
        }
        return null;
    }

    private String evidenceBackedKnowledgeReply(
            String question, NlpIntentClassifier.IntentAnalysis nlpIntent,
            RagRetrievalService.RetrievalResult retrieval) {
        if (!hasText(question) || retrieval == null || !retrieval.answerable()) return null;
        String productUsageReply = verifiedProductUsageReply(question, nlpIntent, retrieval);
        if (hasText(productUsageReply)) return productUsageReply;
        String evidence = normalizedEvidence(retrieval);
        String alternativePathReply = verifiedAlternativePathReply(
            question, retrieval, evidence);
        if (hasText(alternativePathReply)) return alternativePathReply;

        if (isCompoundRenewalQuestion(question)
                && containsAny(evidence, "依旧可以正常登录", "仍可正常登录", "可以正常登录使用")
                && evidence.contains("查阅") && evidence.contains("下载")
                && containsAny(evidence, "不能进行合同的发起", "不能继续发起新合同", "不能发起新合同",
                    "无法发起新合同")) {
            return EXPIRED_ACCOUNT_POLICY_REPLY;
        }

        String normalizedQuestion = normalizeQuestionForMatching(question);
        boolean asksAutomaticReminder = containsAny(normalizedQuestion,
            "自动催", "自动提醒", "系统能自动", "系统会自动");
        boolean reminderEvidence = evidence.contains("自动") && evidence.contains("短信")
            && evidence.contains("微信") && evidence.contains("钉钉");
        if (asksAutomaticReminder && reminderEvidence) {
            boolean asksManualSharing = containsAny(normalizedQuestion,
                "链接", "二维码", "码发", "发过去", "手动");
            boolean manualEvidence = evidence.contains("手动") && evidence.contains("链接")
                && evidence.contains("二维码");
            return asksManualSharing && manualEvidence
                ? AUTOMATIC_SIGNING_REMINDER_REPLY + MANUAL_SIGNING_REMINDER_REPLY
                : AUTOMATIC_SIGNING_REMINDER_REPLY;
        }
        return null;
    }

    private String verifiedProductUsageReply(
            String question,
            NlpIntentClassifier.IntentAnalysis nlpIntent,
            RagRetrievalService.RetrievalResult retrieval) {
        if (nlpIntent == null
                || nlpIntent.intentCode() != NlpIntentClassifier.IntentCode.PRODUCT_USAGE
                || !isBroadProductUsageQuestion(question)) {
            return null;
        }
        String structuredAnswer = structuredKnowledgeFallback(retrieval);
        if (!hasText(structuredAnswer)) return null;
        String normalizedAnswer = normalizeQuestionForMatching(structuredAnswer);
        boolean preservesStandaloneAppBoundary = containsAny(normalizedAnswer,
            "不提供独立手机app", "不支持独立手机app", "没有独立手机app");
        boolean identifiesVerifiedEntry = containsAny(normalizedAnswer,
            "微信公众号", "微信小程序", "pc网页版", "短信签署链接");
        return preservesStandaloneAppBoundary && identifiesVerifiedEntry
            ? structuredAnswer + "\n\n" + PRODUCT_USAGE_CLARIFICATION_REPLY : null;
    }

    private boolean isBroadProductUsageQuestion(String question) {
        String normalized = normalizeQuestionForMatching(question)
            .replace("点签电子合同", "")
            .replace("点签平台", "")
            .replace("点签", "")
            .replace("电子合同", "");
        return List.of(
            "怎么使用", "如何使用", "怎样使用", "怎么用", "如何用",
            "使用方法", "在哪里使用", "怎么操作", "如何操作")
            .contains(normalized);
    }

    private String verifiedAlternativePathReply(
            String question, RagRetrievalService.RetrievalResult retrieval,
            String normalizedEvidence) {
        if (!isPolaritySensitiveQuestion(question)
                || retrieval.citations() == null || retrieval.citations().size() != 1
                || !containsAny(normalizedEvidence,
                    "不支持", "不提供", "不能", "无法", "不可")
                || !containsAny(normalizedEvidence,
                    "可以通过", "可通过", "可以使用", "可使用", "可用",
                    "可以在", "可在", "改用", "替代", "仍可", "还可以")) {
            return null;
        }
        String structuredAnswer = structuredKnowledgeFallback(retrieval);
        if (!hasText(structuredAnswer)) return null;
        String normalizedAnswer = normalizeQuestionForMatching(structuredAnswer);
        boolean preservesNegativeBoundary = containsAny(normalizedAnswer,
            "不支持", "不提供", "不能", "无法", "不可");
        boolean preservesAlternativePath = containsAny(normalizedAnswer,
            "可以通过", "可通过", "可以使用", "可使用", "可用",
            "可以在", "可在", "改用", "替代", "仍可", "还可以");
        return preservesNegativeBoundary && preservesAlternativePath
            ? structuredAnswer : null;
    }

    private String normalizedEvidence(RagRetrievalService.RetrievalResult retrieval) {
        return normalizeQuestionForMatching(evidenceText(retrieval));
    }

    private String evidenceText(RagRetrievalService.RetrievalResult retrieval) {
        StringBuilder evidence = new StringBuilder();
        if (retrieval == null) return "";
        if (hasText(retrieval.context())) evidence.append(retrieval.context()).append('\n');
        if (hasText(retrieval.directAnswerText())) {
            evidence.append(retrieval.directAnswerText()).append('\n');
        }
        if (retrieval.citations() != null) {
            for (Map<String, Object> citation : retrieval.citations()) {
                if (citation != null) {
                    evidence.append(Objects.toString(citation.get("snippet"), "")).append('\n');
                }
            }
        }
        return evidence.toString();
    }

    private String normalizeQuestionForMatching(String value) {
        if (!hasText(value)) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s，。！？?、；;：:（）()【】\\[\\]“”\"']+", "");
    }

    private boolean isCompoundRenewalQuestion(String question) {
        String normalized = normalizeQuestionForMatching(question);
        boolean renewal = containsAny(normalized,
            "不续费", "不再续费", "不买套餐", "不购买套餐", "套餐到期", "套餐过期");
        if (!renewal) return false;
        int topics = 0;
        if (containsAny(normalized, "账号", "账户", "登录")) topics++;
        if (containsAny(normalized, "以前签完的合同", "历史合同", "以前的合同", "之前的合同",
                "合同还能看", "合同还能查", "合同还能下载", "数据还在")) topics++;
        if (containsAny(normalized, "发新合同", "新合同", "继续发起", "还能发起")) topics++;
        return topics >= 2;
    }

    private boolean isCompoundQuestion(String question) {
        if (!hasText(question)) return false;
        if (isCompoundRenewalQuestion(question)) return true;
        String normalized = normalizeQuestionForMatching(question);
        long questionMarks = question.chars().filter(ch -> ch == '?' || ch == '？').count();
        if (questionMarks >= 2) return true;
        int interrogatives = 0;
        for (String term : List.of("能否", "是否", "能不能", "可不可以", "怎么", "多少", "多久", "哪些")) {
            if (normalized.contains(term)) interrogatives++;
        }
        return interrogatives >= 2 && containsAny(normalized, "还", "以及", "同时", "另外", "并且");
    }

    private boolean isPolaritySensitiveQuestion(String question) {
        String normalized = normalizeQuestionForMatching(question);
        return containsAny(normalized, "支持", "不支持", "自动", "手动", "保留", "删除", "收费", "免费");
    }

    private boolean containsAny(String text, String... terms) {
        return java.util.Arrays.stream(terms).anyMatch(text::contains);
    }

    private boolean isCompanyIntroductionQuestion(String question) {
        if (!hasText(question)) return false;
        String normalized = question.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s，。！？、；：,.!?]", "")
            .replaceFirst("^(请问|我想问一下|想问一下|我想了解一下|想了解一下)", "");
        return normalized.matches("(你们公司|贵公司|咱们公司|你们企业|贵企业)"
                + "(怎么样|如何|是?做什么的?|主要是?做什么的?|是?干什么的?|是?干嘛的?|"
                + "介绍一下|有哪些业务|业务是什么)")
            || normalized.matches("(能|可以)?介绍一下(你们|贵|咱们)(公司|企业)吗?");
    }

    private boolean hasCompanyIntroductionEvidence(
            RagRetrievalService.RetrievalResult retrieval) {
        if (retrieval == null || !retrieval.answerable() || retrieval.citations() == null) {
            return false;
        }
        return retrieval.citations().stream()
            .map(citation -> Objects.toString(citation.get("title"), ""))
            .anyMatch(this::isCompanyIntroductionKnowledgeTitle);
    }

    private boolean isCompanyIntroductionKnowledgeTitle(String title) {
        if (!hasText(title)) return false;
        String normalized = title.replaceAll("\\s+", "");
        if (normalized.contains("公司介绍") || normalized.contains("企业介绍")
                || normalized.contains("关于我们") || normalized.contains("主营业务")) {
            return true;
        }
        boolean companySubject = normalized.contains("你们") || normalized.contains("公司")
            || normalized.contains("企业");
        return companySubject && (normalized.contains("做什么")
            || normalized.contains("干什么") || normalized.contains("干嘛"));
    }

    private boolean isDetailedListQuestion(String question) {
        if (!hasText(question)) return false;
        String normalized = question.replaceAll("\\s+", "");
        return normalized.contains("有几种") || normalized.contains("有哪几种")
            || normalized.contains("有哪些") || normalized.contains("哪几种")
            || normalized.contains("几类") || normalized.contains("几种模式")
            || normalized.contains("几种类型") || normalized.contains("包括哪些")
            || ((normalized.contains("模式") || normalized.contains("类型"))
                && (normalized.contains("分别") || normalized.contains("介绍")));
    }

    private boolean isOperationalQuestion(String question) {
        if (!hasText(question)) return false;
        String normalized = question.replaceAll("\\s+", "");
        return normalized.contains("怎么使用") || normalized.contains("如何使用")
            || normalized.contains("怎么操作") || normalized.contains("如何操作")
            || normalized.contains("怎么上传") || normalized.contains("如何上传")
            || normalized.contains("操作步骤") || normalized.contains("使用步骤")
            || normalized.contains("使用方法") || normalized.contains("操作方法")
            || normalized.contains("怎么用") || normalized.contains("如何用")
            || normalized.contains("办理流程") || normalized.contains("操作流程");
    }

    private boolean isContractLaunchMethodQuestion(String question) {
        if (!hasText(question)) return false;
        String normalized = question.replaceAll("\\s+", "");
        boolean contractLaunch = normalized.contains("发起合同")
            || normalized.contains("合同发起");
        boolean asksMethod = normalized.contains("方式") || normalized.contains("几种")
            || normalized.contains("哪些") || normalized.contains("哪种");
        return contractLaunch && asksMethod;
    }

    private SystemPromptResolution nativeFallbackSystemPromptFor(
            EmotionService.EmotionResult emotion) {
        String basePrompt = hasText(nativeFallbackSystemPrompt)
            ? nativeFallbackSystemPrompt : DEFAULT_NATIVE_FALLBACK_SYSTEM_PROMPT;
        String prompt = basePrompt;
        if (emotion != null && emotion.label() != EmotionService.EmotionLabel.NEUTRAL) {
            prompt += "\n当前用户情绪服务策略：" + emotion.instruction();
        }
        prompt += "\n\n强制事实与安全边界：\n" + promptProvider.mandatoryPolicy();
        prompt += "\n\n" + NATIVE_FALLBACK_SCOPE_POLICY;
        String source = hasText(nativeFallbackSystemPrompt)
            ? "configured_native" : "built_in_native";
        PromptTrace trace = new PromptTrace(true, "native_fallback", source,
            CustomerServicePromptProvider.fingerprint(basePrompt),
            CustomerServicePromptProvider.fingerprint(prompt), prompt.length(), 0);
        return new SystemPromptResolution(prompt, trace);
    }

    private Map<String, Object> emotionDetails(EmotionService.EmotionResult emotion) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("label", emotion.label().name());
        details.put("displayLabel", emotion.displayLabel());
        details.put("score", emotion.confidence());
        details.put("trend", emotion.trend().name());
        details.put("negativeStreak", emotion.negativeStreak());
        details.put("risk", emotion.risk().name());
        details.put("shouldHandoff", emotion.shouldHandoff());
        details.put("priority", emotion.priority());
        return details;
    }

    private void updateConversationEmotion(BotConversation conversation,
                                           EmotionService.EmotionResult emotion) {
        conversation.setEmotionLabel(emotion.label().name());
        conversation.setEmotionScore(emotion.confidence());
        conversation.setEmotionTrend(emotion.trend().name());
        conversation.setNegativeStreak(emotion.negativeStreak());
        conversation.setEmotionRisk(emotion.risk().name());
        if (emotion.risk() != EmotionService.EmotionRisk.LOW
                && priorityRank(emotion.priority()) < priorityRank(conversation.getPriority())) {
            conversation.setPriority(emotion.priority());
        }
        conversationService.updateStatus(conversation);
    }

    private void addHandoffDetails(Map<String, Object> response,
                                   HandoffCoordinator.HandoffResult handoff) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("success", handoff.success());
        details.put("ticketId", handoff.ticketId());
        details.put("created", handoff.created());
        details.put("summary", handoff.summary());
        details.put("error", handoff.error());
        response.put("handoff", details);
    }

    private void addRedactionDetails(Map<String, Object> response, Set<String> redactedTypes) {
        response.put("redactionApplied", !redactedTypes.isEmpty());
        response.put("redactedTypes", List.copyOf(redactedTypes));
    }

    private Map<String, Object> withPromptVersion(Map<String, Object> response,
                                                  String promptVersion) {
        response.put("promptVersion", promptVersion);
        response.putIfAbsent("promptApplied", false);
        response.putIfAbsent("promptPath", "none");
        response.putIfAbsent("promptSource", "not_used");
        response.putIfAbsent("promptInvocations", Collections.emptyList());
        response.putIfAbsent("modelProtocolViolations", Collections.emptyList());
        response.putIfAbsent("modelProtocolViolationDetails", Collections.emptyList());
        response.putIfAbsent("modelInvocationCount", 0);
        response.putIfAbsent("modelInputTokens", 0);
        response.putIfAbsent("modelOutputTokens", 0);
        return response;
    }

    private PromptTrace addPromptTrace(List<PromptTrace> traces, PromptTrace trace) {
        if (traces == null || trace == null || !trace.applied()) {
            return PromptTrace.notApplied();
        }
        int attempt = (int) traces.stream()
            .filter(existing -> existing.applied() && existing.path().equals(trace.path()))
            .count() + 1;
        PromptTrace invocation = trace.withAttempt(attempt);
        traces.add(invocation);
        return invocation;
    }

    private void addProtocolViolation(List<String> violations,
                                      List<ModelProtocolViolation> details,
                                      PromptTrace invocation,
                                      String violation) {
        if (!hasText(violation)) return;
        if (violations != null) violations.add(violation);
        if (details != null && invocation != null && invocation.applied()) {
            details.add(new ModelProtocolViolation(invocation.path(), invocation.attempt(), violation));
        }
    }

    private void addModelResponse(List<ChatResponse> responses, ChatResponse response) {
        if (responses != null && response != null) responses.add(response);
    }

    private void addModelInvocationDetails(Map<String, Object> target,
                                           List<ChatResponse> responses) {
        List<ChatResponse> safe = responses == null ? Collections.emptyList() : responses;
        target.put("modelInvocationCount", safe.size());
        target.put("modelInputTokens", totalInputTokens(safe));
        target.put("modelOutputTokens", totalOutputTokens(safe));
    }

    private int totalInputTokens(List<ChatResponse> responses) {
        if (responses == null) return 0;
        long total = responses.stream().mapToLong(ChatResponse::getInputTokens).sum();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private int totalOutputTokens(List<ChatResponse> responses) {
        if (responses == null) return 0;
        long total = responses.stream().mapToLong(ChatResponse::getOutputTokens).sum();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private int totalEstimatedCost(List<ChatResponse> responses) {
        if (responses == null) return 0;
        long total = responses.stream().mapToLong(this::estimateCost).sum();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private List<Map<String, Object>> protocolViolationDetails(
            List<ModelProtocolViolation> details) {
        if (details == null || details.isEmpty()) return Collections.emptyList();
        return details.stream().map(detail -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("path", detail.path());
            value.put("attempt", detail.attempt());
            value.put("violation", detail.violation());
            return value;
        }).toList();
    }

    private void addPromptTraceDetails(Map<String, Object> target, List<PromptTrace> traces) {
        List<PromptTrace> applied = traces == null ? Collections.emptyList()
            : traces.stream().filter(PromptTrace::applied).toList();
        target.put("promptApplied", !applied.isEmpty());
        if (applied.isEmpty()) {
            target.put("promptPath", "none");
            target.put("promptSource", "not_used");
            target.put("promptInvocations", Collections.emptyList());
            return;
        }
        PromptTrace effective = applied.get(applied.size() - 1);
        target.put("promptPath", effective.path());
        target.put("promptSource", effective.source());
        target.put("promptAttempt", effective.attempt());
        target.put("promptBaseSha256", effective.baseSha256());
        target.put("promptEffectiveSha256", effective.effectiveSha256());
        target.put("systemPromptChars", effective.systemPromptChars());
        target.put("promptInvocations", applied.stream().map(this::promptTraceDetails).toList());
    }

    private Map<String, Object> promptTraceDetails(PromptTrace trace) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("path", trace.path());
        details.put("attempt", trace.attempt());
        details.put("source", trace.source());
        details.put("baseSha256", trace.baseSha256());
        details.put("effectiveSha256", trace.effectiveSha256());
        details.put("systemPromptChars", trace.systemPromptChars());
        return details;
    }

    private String redact(String value, Set<String> redactedTypes) {
        SensitiveDataService.RedactionResult result = sensitiveDataService.redact(value);
        redactedTypes.addAll(result.types());
        return result.text();
    }

    private List<Map<String, Object>> redactMaps(List<Map<String, Object>> values,
                                                 Set<String> redactedTypes) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> redacted = new ArrayList<>();
        for (Map<String, Object> value : values) {
            Map<String, Object> safe = new LinkedHashMap<>();
            if (value != null) {
                value.forEach((key, item) -> safe.put(key,
                    redactStructured(item, redactedTypes)));
            }
            redacted.add(safe);
        }
        return redacted;
    }

    private Object redactStructured(Object value, Set<String> redactedTypes) {
        if (value instanceof String text) return redact(text, redactedTypes);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> safe.put(Objects.toString(key),
                redactStructured(item, redactedTypes)));
            return safe;
        }
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>();
            for (Object item : list) safe.add(redactStructured(item, redactedTypes));
            return safe;
        }
        return value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.debug("Could not serialize dialog trace: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String configuredReply(String configured, String fallback) {
        return hasText(configured) ? configured : fallback;
    }

    private enum NativeFallbackDecision {
        NATIVE,
        OUT_OF_SCOPE,
        CLARIFY,
        CONTRACT_DRAFTING_CLARIFY,
        CONTRACT_CAPABILITY_NO_EVIDENCE,
        CONTRACT_LEGAL_RISK,
        HIGH_RISK,
        DISABLED
    }

    private enum BasicConversationIntent {
        IDENTITY("identity", BASIC_IDENTITY_REPLY),
        CAPABILITY("capability", BASIC_CAPABILITY_REPLY),
        GREETING("greeting", BASIC_GREETING_REPLY),
        THANKS("thanks", BASIC_THANKS_REPLY),
        GOODBYE("goodbye", BASIC_GOODBYE_REPLY),
        OFFICIAL_WEBSITE("official_website", OFFICIAL_WEBSITE_REPLY),
        CA_PRODUCT_CLARIFICATION("ca_product_clarification", CA_PRODUCT_CLARIFICATION_REPLY),
        UKEY_PRODUCT_CLARIFICATION("ukey_product_clarification", UKEY_PRODUCT_CLARIFICATION_REPLY),
        UKEY_DIANQIAN_LIMIT("ukey_dianqian_limit", UKEY_DIANQIAN_LIMIT_REPLY);

        private final String code;
        private final String reply;

        BasicConversationIntent(String code, String reply) {
            this.code = code;
            this.reply = reply;
        }

        private String code() {
            return code;
        }

        private String reply() {
            return reply;
        }
    }

    private enum AnswerDecision {
        ANSWER,
        ANSWER_PARTIAL,
        CLARIFY,
        HANDOFF,
        NO_KNOWLEDGE
    }

    private enum PriceVolumeBand {
        STANDARD,
        ENTERPRISE,
        UNKNOWN
    }

    private record NativeFallbackResponse(
            String replyText, String source, String answerStatus, String answerMode,
            String decision, AnswerDecision answerDecision, boolean highRiskNoKnowledge,
            boolean recordUnmatchedQuestion, ChatResponse aiResponse, String modelPrompt,
            String protocolViolation, PromptTrace promptTrace) {
        private NativeFallbackResponse(
                String replyText, String source, String answerStatus, String answerMode,
                String decision, AnswerDecision answerDecision, boolean highRiskNoKnowledge,
                boolean recordUnmatchedQuestion, ChatResponse aiResponse, String modelPrompt) {
            this(replyText, source, answerStatus, answerMode, decision, answerDecision,
                highRiskNoKnowledge, recordUnmatchedQuestion, aiResponse, modelPrompt,
                null, PromptTrace.notApplied());
        }
    }

    private record SystemPromptResolution(String content, PromptTrace trace) {}

    private record PromptTrace(
            boolean applied, String path, String source, String baseSha256,
            String effectiveSha256, int systemPromptChars, int attempt) {
        private static PromptTrace notApplied() {
            return new PromptTrace(false, "none", "not_used", null, null, 0, 0);
        }

        private PromptTrace withAttempt(int value) {
            return new PromptTrace(applied, path, source, baseSha256,
                effectiveSha256, systemPromptChars, value);
        }
    }

    private record ModelProtocolViolation(String path, int attempt, String violation) {}
}
