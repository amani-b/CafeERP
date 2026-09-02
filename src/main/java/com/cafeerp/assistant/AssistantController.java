package com.cafeerp.assistant;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cafeerp.assistant.AssistantService.AssistantReply;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/assistant")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final AssistantService assistantService;
    private final UserRepository userRepository;

    public AssistantController(AssistantService assistantService,
                               UserRepository userRepository) {
        this.assistantService = assistantService;
        this.userRepository = userRepository;
    }

    /**
     * POST /assistant/chat — any authenticated user can chat with the assistant.
     * <p>
     * This method has a hard outer catch-all: any unhandled exception anywhere
     * in the chat flow (Tier 2 matching, AI provider calls, tool dispatch,
     * persistence, etc.) will be caught here, logged at ERROR level with full
     * detail, and result in a graceful fallback message returned to the user.
     * The user will never see a bare error or stack trace.
     */
    @PostMapping("/chat")
    public ResponseEntity<AssistantReply> chat(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Optional conversation the UI wants this turn to land in (null =
        // reuse/create the user's current conversation — same as before).
        String conversationIdRaw = body.get("conversationId");
        Long conversationId = null;
        if (conversationIdRaw != null && !conversationIdRaw.isBlank()) {
            try {
                conversationId = Long.parseLong(conversationIdRaw);
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().build();
            }
        }

        try {
            User user = userRepository.findByUsername(userDetails.getUsername())
                    .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database"));

            AssistantReply reply = assistantService.processMessage(
                    user, message, conversationId, autonomyOf(request));
            return ResponseEntity.ok(reply);

        } catch (Exception e) {
            // Hard outer catch-all: ANY unhandled exception in the chat path
            // is caught here, logged in full, and degrades to a graceful fallback.
            log.error("UNHANDLED EXCEPTION in /assistant/chat for user '{}': {}",
                    userDetails.getUsername(), e.toString(), e);

            // Best-effort: try to look up the User entity for a role-scoped fallback
            try {
                User user = userRepository.findByUsername(userDetails.getUsername()).orElse(null);
                if (user != null) {
                    AssistantReply fallback = assistantService.getFallbackReply(user);
                    return ResponseEntity.ok(fallback);
                }
            } catch (Exception lookupFailure) {
                log.error("Failed to look up user for fallback in outer catch-all", lookupFailure);
            }

            // Last-resort fallback if even user lookup failed
            AssistantReply lastResort = new AssistantReply(
                    "I'm sorry, the assistant is temporarily unavailable due to an unexpected error. "
                    + "Please try again later.",
                    List.of());
            return ResponseEntity.ok(lastResort);
        }
    }

    /**
     * GET /assistant/history — current user's own message thread.
     */
    @GetMapping("/history")
    public ResponseEntity<List<AssistantMessage>> history(
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database"));

        List<AssistantMessage> messages = assistantService.getHistory(user);
        return ResponseEntity.ok(messages);
    }

    /**
     * GET /assistant/admin — admin only. Lists users who have assistant activity.
     */
    @GetMapping("/admin")
    public ResponseEntity<List<User>> adminUsers() {
        List<User> users = assistantService.getUsersWithMessages();
        return ResponseEntity.ok(users);
    }

    /**
     * GET /assistant/admin/{userId} — admin only. Full thread for a specific user.
     * <p>
     * Returns a proper REST 404 (empty body) for unknown users instead of letting
     * {@code IllegalArgumentException} fall through to the MVC error-page handler,
     * which would render an HTML page on this JSON endpoint.
     */
    @GetMapping("/admin/{userId}")
    public ResponseEntity<List<AssistantMessage>> adminUserHistory(@PathVariable Long userId) {
        return userRepository.findById(userId)
                .map(user -> ResponseEntity.ok(assistantService.getHistory(user)))
                .orElseGet(() -> {
                    log.warn("Assistant thread requested for unknown user id {}", userId);
                    return ResponseEntity.notFound().build();
                });
    }

    // ---------------------------------------------------------------
    //  Conversations — power the chat history sidebar/overlay
    // ---------------------------------------------------------------

    /**
     * JSON-safe view of a conversation for history lists (never serializes the
     * lazy {@code User} proxy; open-session-in-view is disabled).
     */
    public record ConversationSummary(Long id, String title, String createdAt,
                                      String lastActivityAt, boolean archived) {}

    /**
     * GET /assistant/conversations — the current user's history list, most
     * recent activity first. {@code ?archived=true} returns the soft-archived
     * (recoverable) conversations instead of the default list.
     */
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationSummary>> conversations(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(value = "archived", required = false) String archived) {

        User user = requireUser(userDetails);
        boolean archivedOnly = "true".equals(archived) || "1".equals(archived);
        List<ConversationSummary> list = assistantService.listConversations(user, archivedOnly)
                .stream()
                .map(c -> new ConversationSummary(
                        c.getId(), c.getTitle(),
                        String.valueOf(c.getCreatedAt()), String.valueOf(c.getLastActivityAt()),
                        c.isArchived()))
                .toList();
        return ResponseEntity.ok(list);
    }

    /** POST /assistant/conversations — "New chat": starts a fresh thread. */
    @PostMapping("/conversations")
    public ResponseEntity<ConversationSummary> createConversation(
            @AuthenticationPrincipal UserDetails userDetails) {

        User user = requireUser(userDetails);
        var c = assistantService.createConversation(user);
        return ResponseEntity.ok(new ConversationSummary(
                c.getId(), c.getTitle(),
                String.valueOf(c.getCreatedAt()), String.valueOf(c.getLastActivityAt()),
                c.isArchived()));
    }

    /**
     * GET /assistant/conversations/{id}/messages — switch to a conversation.
     * Only the owner's conversations are accessible (404 otherwise).
     */
    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<List<AssistantMessage>> conversationMessages(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {

        User user = requireUser(userDetails);
        return ResponseEntity.ok(assistantService.getConversationMessages(user, id));
    }

    // ---------------------------------------------------------------
    //  Phase 4 — agentic autonomy setting + action confirm/cancel
    // ---------------------------------------------------------------

    /** Session attribute holding the user's autonomy choice; resets per session. */
    static final String AUTONOMY_SESSION_KEY = "assistant.autonomy";

    public record AutonomyView(String mode) {}

    /**
     * GET /assistant/autonomy — the current session's autonomy mode. Always
     * {@code ALWAYS_CONFIRM} unless the user changed it this session.
     */
    @GetMapping("/autonomy")
    public ResponseEntity<AutonomyView> getAutonomy(HttpServletRequest request) {
        return ResponseEntity.ok(new AutonomyView(autonomyOf(request).name()));
    }

    /**
     * POST /assistant/autonomy — set this session's autonomy mode. Accepts
     * {@code ALWAYS_CONFIRM} (default) or {@code AUTO_LOW_RISK}. The choice is
     * deliberately session-scoped: a fresh login always starts at
     * ALWAYS_CONFIRM.
     */
    @PostMapping("/autonomy")
    public ResponseEntity<AutonomyView> setAutonomy(@RequestBody Map<String, String> body,
                                                    HttpServletRequest request) {
        AgenticAutonomy mode = AgenticAutonomy.parse(body.get("mode"));
        request.getSession(true).setAttribute(AUTONOMY_SESSION_KEY, mode);
        return ResponseEntity.ok(new AutonomyView(mode.name()));
    }

    /** Record-view of one pending action for the confirmation card. */
    public record ActionConfirmationView(Long id, String tool, String description) {}

    /**
     * POST /assistant/actions/{id}/confirm — approve a pending AI-proposed
     * action. Only the proposing user, and only while still pending.
     */
    @PostMapping("/actions/{id}/confirm")
    public ResponseEntity<?> confirmAction(@AuthenticationPrincipal UserDetails userDetails,
                                           @PathVariable Long id,
                                           @RequestBody Map<String, String> body,
                                           HttpServletRequest request) {
        User user = requireUser(userDetails);
        Long conversationId = parseConversationId(body.get("conversationId"));
        try {
            AssistantReply reply = assistantService.confirmPendingAction(user, id, conversationId);
            return ResponseEntity.ok(reply);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * POST /assistant/actions/{id}/cancel — decline a pending AI-proposed
     * action without executing anything.
     */
    @PostMapping("/actions/{id}/cancel")
    public ResponseEntity<?> cancelAction(@AuthenticationPrincipal UserDetails userDetails,
                                          @PathVariable Long id,
                                          @RequestBody Map<String, String> body) {
        User user = requireUser(userDetails);
        Long conversationId = parseConversationId(body.get("conversationId"));
        try {
            AssistantReply reply = assistantService.cancelPendingAction(user, id, conversationId);
            return ResponseEntity.ok(reply);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private Long parseConversationId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The session's autonomy mode; ALWAYS_CONFIRM when unset (new session). */
    private AgenticAutonomy autonomyOf(HttpServletRequest request) {
        Object value = request.getSession(false) != null
                ? request.getSession(false).getAttribute(AUTONOMY_SESSION_KEY)
                : null;
        return value instanceof AgenticAutonomy mode ? mode : AgenticAutonomy.ALWAYS_CONFIRM;
    }

    private User requireUser(UserDetails userDetails) {
        return userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database"));
    }
}