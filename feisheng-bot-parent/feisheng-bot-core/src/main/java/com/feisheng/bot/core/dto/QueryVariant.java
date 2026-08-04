package com.feisheng.bot.core.dto;

import java.util.Objects;

/** A retrieval query and its relative contribution during rank fusion. */
public record QueryVariant(String query, double weight, String purpose, boolean original) {
    public QueryVariant {
        query = Objects.requireNonNull(query, "query").trim();
        purpose = Objects.requireNonNull(purpose, "purpose").trim();
        if (query.isEmpty()) throw new IllegalArgumentException("query must not be blank");
        if (purpose.isEmpty()) throw new IllegalArgumentException("purpose must not be blank");
        if (!Double.isFinite(weight) || weight <= 0.0 || weight > 1.0) {
            throw new IllegalArgumentException("weight must be in (0, 1]");
        }
    }

    public static QueryVariant original(String query) {
        return new QueryVariant(query, 1.0, "original", true);
    }
}
