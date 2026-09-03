package com.cafeerp.assistant;

/**
 * Phase 5 — background transparency. A trace listener receives a short,
 * human-readable status line every time the assistant starts or finishes
 * an action (currently: each tool call in the agentic loop). The SSE
 * streaming endpoint forwards these to the chat UI, where they render as
 * the "what the AI is doing" trace.
 * <p>
 * Text is plain language — never raw tool names or parameter blobs.
 */
@FunctionalInterface
public interface AssistantTraceListener {

    /**
     * @param text  human-readable description, e.g. "Looking up order #482…"
     * @param state "start" while the action runs, "done" when it finished,
     *              "fail" if it errored.
     */
    void onStep(String text, String state);

    /** No-op listener for all non-streaming call paths. */
    AssistantTraceListener NOOP = (text, state) -> { };
}
