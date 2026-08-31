package com.feisheng.bot.admin.service;

/** Persisted lifecycle states for a whole-document migration. */
public enum KnowledgeMigrationStatus {
    PENDING,
    EXTRACTING,
    EMBEDDING,
    CONFLICT_CHECKING,
    REVIEW_REQUIRED,
    READY_TO_SWITCH,
    SWITCHING,
    COMPLETED,
    FAILED,
    STALE;

    public boolean terminal() {
        return this == COMPLETED || this == STALE;
    }
}
