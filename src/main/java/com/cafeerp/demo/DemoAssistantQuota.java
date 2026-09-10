package com.cafeerp.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/**
 * Per-session AI-assistant message budget for the {@code demo} profile.
 * <p>
 * Session-scoped, so every visitor gets their own allowance: a public visitor
 * cannot run up the real AI bill beyond {@link #MAX_MESSAGES_PER_SESSION}
 * turns. When the budget is spent, the assistant answers with a friendly
 * "demo limit reached" message instead of calling any model. The sandbox
 * data itself stays usable — only new assistant turns are gated.
 */
@Component
@Profile("demo")
@SessionScope
public class DemoAssistantQuota {

    /** Assistant turns allowed per visitor session before the friendly cap. */
    public static final int MAX_MESSAGES_PER_SESSION = 15;

    private int used = 0;

    /**
     * Consumes one turn. Returns {@code true} when the turn may proceed,
     * {@code false} when the session already spent its budget.
     */
    public synchronized boolean tryConsume() {
        if (used >= MAX_MESSAGES_PER_SESSION) {
            return false;
        }
        used++;
        return true;
    }

    /** Turns remaining in this session's budget (for UI hints). */
    public synchronized int remaining() {
        return Math.max(0, MAX_MESSAGES_PER_SESSION - used);
    }
}
