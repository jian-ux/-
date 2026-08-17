package com.feisheng.bot.core.service;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies combinations of domain, action, and subject signals for routing only.
 * Unlike {@link IntentService}, this classifier never returns a customer-facing reply.
 */
@Service
public class NlpIntentClassifier {
    private static final List<String> CONTRACT_TERMS = List.of(
        "合同", "电子签约", "电子签名", "签署", "签章", "盖章");
    private static final List<String> DRAFTING_TERMS = List.of(
        "怎么写", "如何写", "怎样写", "起草", "拟定", "拟一份", "代写", "撰写", "范本", "模板");
    private static final List<String> SIGNING_OPERATION_TERMS = List.of(
        "怎么签", "如何签", "怎样签", "发起", "上传", "导入", "发送", "签署", "签字", "盖章", "签章");
    private static final List<String> CAPABILITY_TERMS = List.of(
        "可以签", "能签", "支持签", "是否可以签", "是否能签", "能不能签", "可不可以签", "能否签", "支持");
    private static final List<String> LEGAL_RISK_TERMS = List.of(
        "法律效力", "有法律效力", "是否合法", "合法吗", "合规吗", "违约责任", "赔偿责任", "法律风险", "有效吗");
    private static final List<String> PRODUCT_TERMS = List.of(
        "点签", "你们平台", "贵司平台", "本公司平台", "平台", "产品", "系统");
    private static final List<String> FEATURE_TERMS = List.of(
        "有哪些功能", "有什么功能", "什么功能", "有哪些能力", "有什么能力", "能做什么", "支持什么", "产品介绍", "平台介绍");
    private static final List<String> USAGE_TERMS = List.of(
        "怎么使用", "如何使用", "怎样使用", "怎么用", "如何用", "使用方法", "在哪里使用", "怎么操作", "如何操作");
    private static final List<String> ACCOUNT_TERMS = List.of(
        "账号", "账户", "注册", "登录", "认证", "实名", "密码");
    private static final List<String> ACCOUNT_ACTION_TERMS = List.of(
        "怎么", "如何", "怎样", "申请", "创建", "开通", "重置", "修改", "找回", "无法", "失败");
    private static final List<String> SUBJECT_MARKERS = List.of(
        "是否可以签", "可不可以签", "能不能签", "是否能签", "支持签", "可以签", "能否签", "能签",
        "怎么签", "如何签", "怎样签", "我要签", "想签", "支持", "发起", "上传", "起草", "撰写", "代写", "拟定", "写");
    private static final List<String> SUBJECT_PREFIXES = List.of(
        "请问", "咨询", "关于", "我想了解", "我想咨询", "我想问", "我要", "想要", "帮我",
        "我已经有", "已经有", "已有", "现成的", "有一份",
        "你们平台", "贵司平台", "本公司平台", "点签平台", "点签", "平台", "系统");
    private static final List<String> GENERALLY_SUPPORTED_CONTRACT_SUFFIXES = List.of(
        "房屋买卖合同", "房产买卖合同", "商品房买卖合同", "二手房买卖合同");

    public IntentAnalysis classify(String text) {
        if (text == null || text.isBlank()) return unknown("");
        String original = text.trim();
        String normalized = normalize(original);
        Set<String> signals = new LinkedHashSet<>();
        Set<String> entities = new LinkedHashSet<>();
        Set<String> actions = new LinkedHashSet<>();

        String contractSignal = firstMatch(normalized, CONTRACT_TERMS);
        String draftingSignal = firstMatch(normalized, DRAFTING_TERMS);
        String signingSignal = firstMatch(normalized, SIGNING_OPERATION_TERMS);
        String capabilitySignal = firstMatch(normalized, CAPABILITY_TERMS);
        if (capabilitySignal == null
                && normalized.matches(".*(?:可以|能|支持).{1,8}(?:签|签署|签约).*")) {
            capabilitySignal = "跨词签署能力";
        }
        String legalSignal = firstMatch(normalized, LEGAL_RISK_TERMS);
        String productSignal = firstMatch(normalized, PRODUCT_TERMS);
        String subject = extractContractSubject(normalized);

        addSignal(signals, entities, "domain", contractSignal);
        addSignal(signals, entities, "product", productSignal);
        if (subject != null) entities.add(subject);

        if (contractSignal != null || subject != null) {
            if (legalSignal != null) {
                addSignal(signals, actions, "legal", legalSignal);
                return result(IntentCode.CONTRACT_LEGAL_RISK, "contract", RiskLevel.HIGH,
                    entities, actions, false, original, signals, subject, false);
            }
            if (draftingSignal != null) {
                addSignal(signals, actions, "drafting", draftingSignal);
                String target = subject == null ? "合同" : subject;
                return result(IntentCode.CONTRACT_DRAFTING, "contract", RiskLevel.MEDIUM,
                    entities, actions, true, target + " 内容怎么写 起草模板", signals,
                    subject, false);
            }
            if (capabilitySignal != null) {
                addSignal(signals, actions, "capability", capabilitySignal);
                String target = subject == null ? "具体合同类型" : subject;
                boolean generallySupported = isGenerallySupportedContractSubject(subject);
                return result(IntentCode.CONTRACT_TYPE_CAPABILITY, "contract", RiskLevel.MEDIUM,
                    entities, actions, subject == null || !isSpecificContractSubject(subject),
                    "点签 是否支持签署 " + target, signals, subject,
                    isSpecificContractSubject(subject) && !generallySupported,
                    generallySupported);
            }
            if (signingSignal != null) {
                addSignal(signals, actions, "operation", signingSignal);
                boolean ambiguousUpload = isAmbiguousContractUpload(normalized);
                String signingRetrievalQuery = ambiguousUpload
                    ? "发起合同有几种方式？ 已有合同文件怎么上传发起签署，"
                        + "已签纸质合同怎么上传归档？"
                    : original;
                return result(IntentCode.CONTRACT_SIGNING_OPERATION, "contract", RiskLevel.LOW,
                    entities, actions, ambiguousUpload || productSignal == null,
                    signingRetrievalQuery, signals, subject, false);
            }
        }

        String featureSignal = firstMatch(normalized, FEATURE_TERMS);
        if (productSignal != null && featureSignal != null) {
            addSignal(signals, actions, "features", featureSignal);
            return result(IntentCode.PRODUCT_FEATURES, "product", RiskLevel.LOW,
                entities, actions, false, "点签电子合同主要包含的7大功能", signals,
                null, false);
        }

        String usageSignal = firstMatch(normalized, USAGE_TERMS);
        if (productSignal != null && usageSignal != null) {
            addSignal(signals, actions, "usage", usageSignal);
            return result(IntentCode.PRODUCT_USAGE, "product", RiskLevel.LOW,
                entities, actions, false, original, signals, null, false);
        }

        String accountSignal = firstMatch(normalized, ACCOUNT_TERMS);
        String accountAction = firstMatch(normalized, ACCOUNT_ACTION_TERMS);
        if (accountSignal != null && accountAction != null) {
            addSignal(signals, entities, "account", accountSignal);
            addSignal(signals, actions, "account_action", accountAction);
            return result(IntentCode.ACCOUNT_OPERATION, "account", RiskLevel.MEDIUM,
                entities, actions, false, original, signals, accountSignal, false);
        }

        return unknown(original);
    }

    private IntentAnalysis unknown(String original) {
        return new IntentAnalysis(IntentCode.UNKNOWN, "unknown", RiskLevel.LOW,
            List.of(), List.of(), false, original, List.of(), null, false, false);
    }

    private IntentAnalysis result(IntentCode code, String domain, RiskLevel riskLevel,
                                  Set<String> entities, Set<String> actions,
                                  boolean needsClarification, String retrievalQuery,
                                  Set<String> signals, String subject,
                                  boolean requiresSpecificEvidence) {
        return result(code, domain, riskLevel, entities, actions, needsClarification,
            retrievalQuery, signals, subject, requiresSpecificEvidence, false);
    }

    private IntentAnalysis result(IntentCode code, String domain, RiskLevel riskLevel,
                                  Set<String> entities, Set<String> actions,
                                  boolean needsClarification, String retrievalQuery,
                                  Set<String> signals, String subject,
                                  boolean requiresSpecificEvidence,
                                  boolean generallySupportedContractType) {
        return new IntentAnalysis(code, domain, riskLevel, List.copyOf(entities),
            List.copyOf(actions), needsClarification, retrievalQuery,
            List.copyOf(signals), subject, requiresSpecificEvidence,
            generallySupportedContractType);
    }

    private void addSignal(Set<String> signals, Set<String> values,
                           String signalType, String value) {
        if (value == null) return;
        signals.add(signalType + ":" + value);
        values.add(value);
    }

    private String firstMatch(String text, List<String> terms) {
        return terms.stream()
            .filter(text::contains)
            .max((left, right) -> Integer.compare(left.length(), right.length()))
            .orElse(null);
    }

    private String extractContractSubject(String text) {
        int contractEnd = text.indexOf("合同");
        if (contractEnd < 0) return null;
        contractEnd += "合同".length();
        String beforeContract = text.substring(0, contractEnd);
        int subjectStart = 0;
        for (String marker : SUBJECT_MARKERS) {
            int markerIndex = beforeContract.lastIndexOf(marker);
            if (markerIndex >= 0) subjectStart = Math.max(subjectStart, markerIndex + marker.length());
        }
        String subject = beforeContract.substring(subjectStart);
        boolean changed;
        do {
            changed = false;
            for (String prefix : SUBJECT_PREFIXES) {
                if (subject.startsWith(prefix)) {
                    subject = subject.substring(prefix.length());
                    changed = true;
                }
            }
        } while (changed && !subject.isEmpty());
        if (!subject.endsWith("合同") || subject.length() > 14) return "合同";
        return subject;
    }

    private boolean isSpecificContractSubject(String subject) {
        return subject != null && !List.of("合同", "电子合同", "线上合同", "纸质合同")
            .contains(subject);
    }

    private boolean isGenerallySupportedContractSubject(String subject) {
        if (subject == null) return false;
        return GENERALLY_SUPPORTED_CONTRACT_SUFFIXES.stream().anyMatch(subject::endsWith);
    }

    private boolean isAmbiguousContractUpload(String text) {
        if (!text.contains("上传")) return false;
        return text.contains("已经有合同") || text.contains("已有合同")
            || text.contains("现成合同") || text.contains("合同文件")
            || text.contains("有一份合同");
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s，。！？?、；;：:（）()【】\\[\\]“”\"']+", "");
    }

    public enum IntentCode {
        CONTRACT_DRAFTING,
        CONTRACT_SIGNING_OPERATION,
        CONTRACT_TYPE_CAPABILITY,
        CONTRACT_LEGAL_RISK,
        PRODUCT_FEATURES,
        PRODUCT_USAGE,
        ACCOUNT_OPERATION,
        UNKNOWN
    }

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    public record IntentAnalysis(
            IntentCode intentCode,
            String domain,
            RiskLevel riskLevel,
            List<String> entities,
            List<String> actions,
            boolean needsClarification,
            String retrievalQuery,
            List<String> matchedSignals,
            String subject,
            boolean requiresSpecificEvidence,
            boolean generallySupportedContractType) {
    }
}
