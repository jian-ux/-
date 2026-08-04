package com.feisheng.bot.knowledge.service;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.util.ArrayList;
import java.util.List;

final class PhoneticSimilarity {
    static final int MIN_QUERY_TOKENS = 6;

    private static final HanyuPinyinOutputFormat PINYIN_FORMAT = pinyinFormat();

    private PhoneticSimilarity() {}

    static double bestSubstringScore(String query, String candidate) {
        List<String> queryTokens = tokens(query);
        if (queryTokens.size() < MIN_QUERY_TOKENS) return 0;

        List<String> candidateTokens = tokens(candidate);
        if (candidateTokens.isEmpty()) return 0;

        int distance = substringEditDistance(queryTokens, candidateTokens);
        return Math.max(0, 1.0 - (double) distance / queryTokens.size());
    }

    private static int substringEditDistance(List<String> query, List<String> candidate) {
        int[] previous = new int[candidate.size() + 1];
        int[] current = new int[candidate.size() + 1];

        // A candidate prefix is free, so the result can match anywhere in a long chunk.
        for (int i = 1; i <= query.size(); i++) {
            current[0] = i;
            for (int j = 1; j <= candidate.size(); j++) {
                int substitution = previous[j - 1]
                    + (query.get(i - 1).equals(candidate.get(j - 1)) ? 0 : 1);
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                current[j] = Math.min(substitution, Math.min(deletion, insertion));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }

        int best = query.size();
        for (int value : previous) best = Math.min(best, value);
        return best;
    }

    private static List<String> tokens(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) return result;

        for (char character : value.toCharArray()) {
            try {
                String[] readings = PinyinHelper.toHanyuPinyinStringArray(character, PINYIN_FORMAT);
                if (readings != null && readings.length > 0) {
                    result.add(readings[0]);
                } else if (Character.isLetterOrDigit(character)) {
                    result.add(String.valueOf(Character.toLowerCase(character)));
                }
            } catch (BadHanyuPinyinOutputFormatCombination ignored) {
                if (Character.isLetterOrDigit(character)) {
                    result.add(String.valueOf(Character.toLowerCase(character)));
                }
            }
        }
        return result;
    }

    private static HanyuPinyinOutputFormat pinyinFormat() {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        return format;
    }
}
