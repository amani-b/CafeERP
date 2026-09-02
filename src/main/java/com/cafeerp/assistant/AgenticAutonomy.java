package com.cafeerp.assistant;

/**
 * Per-session autonomy level for agentic AI actions (Phase 4). Lives in the
 * user's HTTP session, so it resets — and therefore defaults to
 * {@link #ALWAYS_CONFIRM} — at the start of every new session.
 * <p>
 * There is deliberately NO "fully autonomous" tier: anything that creates or
 * modifies an order ALWAYS requires confirmation in both modes, because the
 * money/inventory impact is too high for a setting users forget they set.
 */
public enum AgenticAutonomy {

    /** Every write action requires an explicit user confirmation (default). */
    ALWAYS_CONFIRM,

    /**
     * Reads and non-destructive updates (inventory counts) run automatically;
     * order-impacting actions always require confirmation.
     */
    AUTO_LOW_RISK;

    public static AgenticAutonomy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALWAYS_CONFIRM;
        }
        return switch (raw.trim().toLowerCase()) {
            case "auto", "auto_low_risk", "autolowrisk" -> AUTO_LOW_RISK;
            default -> ALWAYS_CONFIRM;
        };
    }
}