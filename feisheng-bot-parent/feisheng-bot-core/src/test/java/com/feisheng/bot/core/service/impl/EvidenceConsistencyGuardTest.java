package com.feisheng.bot.core.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceConsistencyGuardTest {

    @Test
    void detectsConflictingMinimumUnitPrices() {
        String evidence = "电子合同签署单价最低仅需3元/份。"
            + "正式使用时按签署份数计费，如单份低至5元。";

        assertTrue(EvidenceConsistencyGuard.hasConflictingScalarFacts(
            "点签电子合同怎么收费？", evidence));
    }

    @Test
    void allowsDifferentPricesForDifferentPackageVariants() {
        String evidence = "专业版1999元，含1000份的专业版3999元；"
            + "高级版3999元，含1000份的高级版5999元。";

        assertFalse(EvidenceConsistencyGuard.hasConflictingScalarFacts(
            "专业版和高级版分别多少钱？", evidence));
    }

    @Test
    void rejectsMostlyUnsupportedMaterialLists() {
        String evidence = "企业用户需提交营业执照等资质信息。"
            + "企业认证可通过法人在线认证、法人远程授权或对公打款完成。";
        String reply = "企业认证需要准备：\n"
            + "1. 营业执照副本复印件；\n"
            + "2. 法人身份证正反面复印件；\n"
            + "3. 组织机构代码证复印件；\n"
            + "4. 税务登记证复印件。";

        assertTrue(EvidenceConsistencyGuard.hasUnsupportedEnumeratedFacts(
            "企业实名认证需要准备什么材料？", evidence, reply));
    }

    @Test
    void allowsEnumeratedMaterialsExplicitlyPresentInEvidence() {
        String evidence = "所需材料为营业执照、法人身份证和授权委托书。";
        String reply = "需要准备：\n1. 营业执照；\n2. 法人身份证；\n3. 授权委托书。";

        assertFalse(EvidenceConsistencyGuard.hasUnsupportedEnumeratedFacts(
            "需要提供哪些材料？", evidence, reply));
    }

    @Test
    void rejectsMostlyUnsupportedOperationalSteps() {
        String evidence = "点签可以在线完成合同发起、身份核验、在线签署和签章管理。";
        String reply = "操作步骤：\n"
            + "1. 打开手机应用商店下载安装点签 APP。\n"
            + "2. 注册并登录账号。\n"
            + "3. 在我的页面点击我的合同。\n"
            + "4. 选择合同模板。\n"
            + "5. 进行身份核验。";

        assertTrue(EvidenceConsistencyGuard.hasUnsupportedProceduralSteps(
            "点签怎么使用？", evidence, reply));
    }

    @Test
    void allowsOperationalStepsCoveredByEvidence() {
        String evidence = "进入合同详情页面后点击撤回，补充附件后重新发起合同。";
        String reply = "操作步骤：\n1. 进入合同详情页面。\n2. 点击撤回。\n"
            + "3. 补充遗漏的附件。\n4. 重新发起合同。";

        assertFalse(EvidenceConsistencyGuard.hasUnsupportedProceduralSteps(
            "合同怎么撤回并补充附件？", evidence, reply));
    }

    @Test
    void detectsStandaloneAppInstallClaimAgainstEvidenceBoundary() {
        String evidence = "目前不提供独立手机 APP，手机用户可使用微信小程序。";
        String reply = "请打开应用商店下载安装点签 APP。";

        assertTrue(EvidenceConsistencyGuard.contradictsStandaloneAppBoundary(
            evidence, reply));
    }

    @Test
    void allowsVerifiedMobileEntryWithoutStandaloneAppClaim() {
        String evidence = "目前不提供独立手机 APP，手机用户可使用微信小程序。";
        String reply = "点签不提供独立手机 APP，可通过微信小程序使用。";

        assertFalse(EvidenceConsistencyGuard.contradictsStandaloneAppBoundary(
            evidence, reply));
    }

    @Test
    void repairsPositiveOpeningThatContradictsDirectOperationBoundary() {
        String evidence = "合同发出后不能直接追加附件，需要先撤回，补齐后重新发起。";
        String reply = "附件还可以补进去。操作步骤如下：\n\n"
            + "1. 先撤回原合同。\n2. 补齐附件后重新发起。";

        assertTrue(EvidenceConsistencyGuard.contradictsNegativeBoundary(
            "附件还能补进去吗？", evidence, reply));
        String repaired = EvidenceConsistencyGuard.repairNegativeBoundary(reply);
        assertTrue(repaired.startsWith("不能直接这样操作。"));
        assertTrue(repaired.contains("先撤回原合同"));
        assertFalse(repaired.contains("还可以补进去"));
    }

    @Test
    void allowsPositiveWordingWhenItClearlyDescribesTheAlternativePath() {
        String evidence = "合同发出后不能直接追加附件，需要先撤回，补齐后重新发起。";
        String reply = "可以先撤回原合同，补齐附件后重新发起。";

        assertFalse(EvidenceConsistencyGuard.contradictsNegativeBoundary(
            "附件还能补进去吗？", evidence, reply));
    }
}
