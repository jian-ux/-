package com.feisheng.bot.core.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable shared diagnostics that can be copied into response-oriented maps. */
public final class DialogResponseMetadata {
    private final Map<String, Object> values;

    public DialogResponseMetadata(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public Map<String, Object> copy() {
        return new LinkedHashMap<>(values);
    }

    public void applyTo(Map<String, Object> target) {
        if (target != null) {
            target.putAll(values);
        }
    }
}
