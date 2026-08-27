package com.cafeerp.assistant;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.cafeerp.user.Role;

/**
 * Hard, server-side access gate for the AI assistant.
 * <p>
 * Runs <b>before</b> any AI provider call or deterministic handler, so
 * restricted topics are enforced structurally rather than by prompt wording:
 * when a decision is {@code deny}, the message is never sent to a model,
 * never passed to tool dispatch, and no data that could answer it is ever
 * fetched. This holds regardless of how the question is phrased ("between us",
 * hypothetical framing, role-play, etc.) because classification is purely
 * lexical on the user's own message.
 * <p>
 * Restricted for STAFF and KITCHEN:
 * <ul>
 *   <li>Financial performance: sales figures, revenue, profit, margins,
 *       cost structures</li>
 *   <li>Pay-related information: wages, salaries, payroll, pay rates</li>
 *   <li>Individual performance: who is fastest/best/slowest among coworkers</li>
 * </ul>
 * ADMIN bypasses the gate entirely (this mirrors what their tools already
 * expose).
 */
@Component
public class AssistantAccessGuard {

    /** Result of evaluating an incoming chat message. */
    public record Decision(boolean allowed, String denialText) {
        static Decision allow() {
            return new Decision(true, null);
        }
        static Decision deny(String denialText) {
            return new Decision(false, denialText);
        }
    }

    private static final String DENIAL_TEXT =
            "That one's outside what I can share — sales numbers, revenue, profit margins, "
            + "payroll details, and how individual team members are doing are manager-only territory. "
            + "I don't route those questions anywhere, so nothing slips out by accident either.\n"
            + "If you need them for something concrete, ask your manager directly. What I'm always "
            + "happy to help with here: menu prices, order lookups, and working through how to run "
            + "a smoother shift.";

    // Financial / sales topics -------------------------------------------------
    private static final List<Pattern> FINANCE_PATTERNS = List.of(
            Pattern.compile("\\bsales?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brevenue\\b|\\btakings\\b|\\bturnover\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bprofit(s|able|ability)?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmargins?\\b|\\bmark-?ups?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(what|how much).{0,40}\\b(making|earning|bring)", Pattern.CASE_INSENSITIVE));

    // Pay / wage topics --------------------------------------------------------
    private static final List<Pattern> PAY_PATTERNS = List.of(
            Pattern.compile("\\bwages?\\b|\\bsalar(y|ies)\\b|\\bpayroll\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(hourly|pay)\\s+rate", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpaid?.{0,15}(per|an|by the)\\s+hour", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btips?(\\s+(pool|split|shared))?\\b", Pattern.CASE_INSENSITIVE));

    // Other people's performance -------------------------------------------------
    private static final List<Pattern> PERFORMANCE_PATTERNS = List.of(
            Pattern.compile("\\bperformance\\b|\\bproductivity\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(best|top|fastest|slowest|weakest|strongest|worst)\\b.{0,40}"
                    + "\\b(barista|cashier|waiter|waitress|server|staff|employee|worker|cook|colleague)s?\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brank(ed|ing|ings)?\\b.{0,40}\\b(staff|employees?|workers?|baristas?)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwho('s| is| was)\\b.{0,30}"
                    + "\\b(fastest|slowest|best|worst|strongest|weakest|most productive)\\b",
                    Pattern.CASE_INSENSITIVE));

    /**
     * Classify an inbound message for the given role.
     *
     * @param role        the authenticated user's role
     * @param userMessage the raw message text
     * @return {@link Decision#allow()} or a friendly {@link Decision#deny}
     */
    public Decision check(Role role, String userMessage) {
        if (role == Role.ADMIN || userMessage == null || userMessage.isBlank()) {
            return Decision.allow();
        }
        boolean sensitive = matchesAny(FINANCE_PATTERNS, userMessage)
                || matchesAny(PAY_PATTERNS, userMessage)
                || matchesAny(PERFORMANCE_PATTERNS, userMessage);
        return sensitive ? Decision.deny(DENIAL_TEXT) : Decision.allow();
    }

    /** Exposed for tests and logging context. */
    public String denialText() {
        return DENIAL_TEXT;
    }

    private static boolean matchesAny(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}