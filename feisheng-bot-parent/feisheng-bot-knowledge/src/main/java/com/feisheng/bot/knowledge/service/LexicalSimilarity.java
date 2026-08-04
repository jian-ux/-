package com.feisheng.bot.knowledge.service;

final class LexicalSimilarity {
    static final int MIN_QUERY_CHARS = 4;

    private LexicalSimilarity() {}

    static double bestSubstringScore(String query, String candidate) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.length() < MIN_QUERY_CHARS) return 0;

        String normalizedCandidate = normalize(candidate);
        if (normalizedCandidate.isEmpty()) return 0;

        int distance = substringEditDistance(normalizedQuery, normalizedCandidate);
        return Math.max(0, 1.0 - (double) distance / normalizedQuery.length());
    }

    private static int substringEditDistance(String query, String candidate) {
        int[] previous = new int[candidate.length() + 1];
        int[] current = new int[candidate.length() + 1];

        for (int i = 1; i <= query.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= candidate.length(); j++) {
                int substitution = previous[j - 1]
                    + (query.charAt(i - 1) == candidate.charAt(j - 1) ? 0 : 1);
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                current[j] = Math.min(substitution, Math.min(deletion, insertion));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }

        int best = query.length();
        for (int value : previous) best = Math.min(best, value);
        return best;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (char character : value.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(character)) result.append(character);
        }
        return result.toString();
    }
}
