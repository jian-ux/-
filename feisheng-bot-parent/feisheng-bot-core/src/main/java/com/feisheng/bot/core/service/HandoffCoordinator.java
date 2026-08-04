package com.feisheng.bot.core.service;

public interface HandoffCoordinator {
    HandoffResult handoff(Long conversationId, String reason, String priority);

    /**
     * Keeps the active handoff ticket useful while a customer is waiting for an
     * agent. Implementations may persist the message in the ticket summary.
     */
    default void recordUserMessage(Long conversationId, String content) {
        // The core module can run without the admin handoff implementation.
    }

    /**
     * Cancels a queued handoff. A handoff already owned by an agent must not be
     * cancelled through this path.
     */
    default boolean cancelWaitingHandoff(Long conversationId, String reason) {
        return false;
    }

    record HandoffResult(boolean success, Long ticketId, boolean created,
                         String summary, String error) {
        public static HandoffResult failed(String error) {
            return new HandoffResult(false, null, false, null, error);
        }
    }
}
