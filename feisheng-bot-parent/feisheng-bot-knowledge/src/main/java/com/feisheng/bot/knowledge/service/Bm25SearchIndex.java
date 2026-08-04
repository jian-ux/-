package com.feisheng.bot.knowledge.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable in-memory BM25 index using Chinese character unigrams/bigrams and ASCII words. */
final class Bm25SearchIndex {
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<IndexedDocument> documents;
    private final Map<String, Integer> documentFrequency;
    private final double averageLength;

    private Bm25SearchIndex(List<IndexedDocument> documents,
                            Map<String, Integer> documentFrequency,
                            double averageLength) {
        this.documents = documents;
        this.documentFrequency = documentFrequency;
        this.averageLength = averageLength;
    }

    static Bm25SearchIndex empty() {
        return new Bm25SearchIndex(Collections.emptyList(), Collections.emptyMap(), 0);
    }

    static Bm25SearchIndex build(List<SourceDocument> sources) {
        if (sources == null || sources.isEmpty()) return empty();

        List<IndexedDocument> indexed = new ArrayList<>();
        Map<String, Integer> frequencies = new HashMap<>();
        long totalTerms = 0;
        for (SourceDocument source : sources) {
            List<String> terms = tokenize(source.text());
            if (terms.isEmpty()) continue;
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String term : terms) termFrequency.merge(term, 1, Integer::sum);
            termFrequency.keySet().forEach(term -> frequencies.merge(term, 1, Integer::sum));
            indexed.add(new IndexedDocument(Map.copyOf(source.payload()),
                Map.copyOf(termFrequency), terms.size()));
            totalTerms += terms.size();
        }
        if (indexed.isEmpty()) return empty();
        return new Bm25SearchIndex(List.copyOf(indexed), Map.copyOf(frequencies),
            (double) totalTerms / indexed.size());
    }

    List<Map<String, Object>> search(String query, int topK, double minScore) {
        return search(query, topK, minScore, Collections.emptyMap());
    }

    List<Map<String, Object>> search(String query, int topK, double minScore,
                                     Map<String, Object> filters) {
        if (query == null || query.isBlank() || topK <= 0 || documents.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> normalizedFilters = PayloadFilters.normalize(filters);
        if (PayloadFilters.isUnsatisfiable(normalizedFilters)) return Collections.emptyList();
        Set<String> queryTerms = new LinkedHashSet<>(tokenize(query));
        if (queryTerms.isEmpty()) return Collections.emptyList();

        List<Map<String, Object>> matches = new ArrayList<>();
        for (IndexedDocument document : documents) {
            if (!PayloadFilters.matches(document.payload(), normalizedFilters)) continue;
            double score = score(queryTerms, document);
            if (score <= 0 || score < minScore) continue;
            Map<String, Object> match = new LinkedHashMap<>(document.payload());
            match.put("bm25Score", round(score));
            match.put("matchMode", "bm25");
            matches.add(match);
        }
        matches.sort((left, right) -> Double.compare(
            number(right.get("bm25Score")), number(left.get("bm25Score"))));
        return matches.size() > topK
            ? new ArrayList<>(matches.subList(0, topK))
            : matches;
    }

    private double score(Set<String> queryTerms, IndexedDocument document) {
        double score = 0;
        double lengthNormalization = K1 * (1 - B + B * document.length() / averageLength);
        for (String term : queryTerms) {
            int termFrequency = document.termFrequency().getOrDefault(term, 0);
            if (termFrequency == 0) continue;
            int containingDocuments = documentFrequency.getOrDefault(term, 0);
            double inverseDocumentFrequency = Math.log(1
                + (documents.size() - containingDocuments + 0.5) / (containingDocuments + 0.5));
            score += inverseDocumentFrequency * termFrequency * (K1 + 1)
                / (termFrequency + lengthNormalization);
        }
        return score;
    }

    static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) return Collections.emptyList();
        String normalized = value.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder chinese = new StringBuilder();
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                flushAscii(ascii, terms);
                chinese.appendCodePoint(codePoint);
            } else {
                flushChinese(chinese, terms);
                if (Character.isLetterOrDigit(codePoint)) {
                    ascii.appendCodePoint(codePoint);
                } else {
                    flushAscii(ascii, terms);
                }
            }
        }
        flushAscii(ascii, terms);
        flushChinese(chinese, terms);
        return terms;
    }

    private static void flushAscii(StringBuilder ascii, List<String> terms) {
        if (!ascii.isEmpty()) terms.add(ascii.toString());
        ascii.setLength(0);
    }

    private static void flushChinese(StringBuilder chinese, List<String> terms) {
        if (chinese.isEmpty()) return;
        int[] codePoints = chinese.codePoints().toArray();
        for (int codePoint : codePoints) terms.add(new String(Character.toChars(codePoint)));
        for (int i = 0; i + 1 < codePoints.length; i++) {
            terms.add(new String(codePoints, i, 2));
        }
        chinese.setLength(0);
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    record SourceDocument(Map<String, Object> payload, String text) {}

    private record IndexedDocument(Map<String, Object> payload,
                                   Map<String, Integer> termFrequency,
                                   int length) {}
}
