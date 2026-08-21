package com.feisheng.bot.core.service;

import java.util.List;
import java.util.Map;

/** Applies only high-confidence domain typo corrections before understanding a query. */
public final class TextCorrectionService {
    private static final List<Map.Entry<String, String>> CORRECTIONS = List.of(
        Map.entry("电子合通", "电子合同"),
        Map.entry("合通", "合同"),
        Map.entry("签暑", "签署"),
        Map.entry("签属", "签署"),
        Map.entry("认正", "认证"),
        Map.entry("验正", "验证"),
        Map.entry("模版", "模板"),
        Map.entry("帐号", "账号"),
        Map.entry("帐户", "账户"),
        Map.entry("下栽", "下载"),
        Map.entry("盖彰", "盖章"),
        Map.entry("盖张", "盖章"),
        Map.entry("发启合同", "发起合同"),
        Map.entry("电孑", "电子"));

    public String correct(String text) {
        if (text == null || text.isBlank()) return text;
        String corrected = text;
        for (Map.Entry<String, String> correction : CORRECTIONS) {
            corrected = corrected.replace(correction.getKey(), correction.getValue());
        }
        return corrected;
    }
}
