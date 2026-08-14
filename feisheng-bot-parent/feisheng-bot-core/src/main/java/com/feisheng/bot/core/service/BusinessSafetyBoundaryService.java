package com.feisheng.bot.core.service;

import com.feisheng.bot.core.dto.SafetyResult;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic authorization and legal-risk boundaries that must run before retrieval.
 */
@Service
public class BusinessSafetyBoundaryService {
    private static final String CROSS_ACCOUNT_REPLY =
        "跨账号查看、获取或发送合同需要先核验您的身份和授权范围，"
            + "我不能直接提供其他账号下的合同，已为您转接人工客服处理。";

    private static final List<String> OTHER_PARTY_TERMS = List.of(
        "另一个", "另一个用户", "其他", "其它", "他人", "别人", "别的用户", "非本人");
    private static final List<String> ACCOUNT_TERMS = List.of(
        "账号", "账户", "用户", "名下");
    private static final List<String> PROTECTED_RESOURCE_TERMS = List.of(
        "合同", "协议", "签约文件", "附件", "签署记录");
    private static final List<String> ACCESS_ACTION_TERMS = List.of(
        "查看", "查询", "查一下", "查下", "查", "获取", "拿到", "调取", "下载", "导出", "打开", "发给", "发送给");

    public SafetyResult check(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return SafetyResult.pass();
        SafetyResult authorization = checkRetrievalAuthorizationNormalized(normalized);
        if (authorization.isBlocked()) return authorization;
        return SafetyResult.pass();
    }

    public SafetyResult checkRetrievalAuthorization(String text) {
        return checkRetrievalAuthorizationNormalized(normalize(text));
    }

    private SafetyResult checkRetrievalAuthorizationNormalized(String normalized) {
        if (isCrossAccountProtectedResourceRequest(normalized)) {
            return SafetyResult.handoff("跨账号合同访问需要授权核验", CROSS_ACCOUNT_REPLY);
        }
        return SafetyResult.pass();
    }

    private boolean isCrossAccountProtectedResourceRequest(String text) {
        return containsAny(text, OTHER_PARTY_TERMS)
            && containsAny(text, ACCOUNT_TERMS)
            && containsAny(text, PROTECTED_RESOURCE_TERMS)
            && containsAny(text, ACCESS_ACTION_TERMS);
    }

    private boolean containsAny(String text, List<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }

    private String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", "")
            .trim();
    }
}
