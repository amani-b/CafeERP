package com.cafeerp.assistant;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cafeerp.assistant.AssistantService.AssistantReply;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Phase 5 — background transparency: SSE-streaming chat endpoint.
 * <p>
 * Behaves exactly like POST /assistant/chat (and its edit/regenerate
 * variants, selected via the {@code mode} field), except that the response
 * is {@code text/event-stream}:
 * <ul>
 *   <li>{@code step} events — {@code {text, state}} — as the assistant
 *       takes each action (tool call), so the UI can show what it is doing
 *       while it happens;</li>
 *   <li>a final {@code reply} event carrying the full {@link AssistantReply}
 *       JSON (identical shape to the non-streaming endpoints), after which
 *       the stream completes.</li>
 * </ul>
 * The non-streaming endpoints remain untouched: they keep serving the
 * legacy clients and act as the UI's automatic fallback if streaming fails.
 */
@RestController
@RequestMapping("/assistant")
public class AssistantStreamController {

    private static final Logger log = LoggerFactory.getLogger(AssistantStreamController.class);

    /** Long enough for several slow provider rounds; bounded so a hung stream can never leak. */
    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(3).toMillis();

    private final AssistantService assistantService;
    private final AssistantTitleService titleService;
    private final UserRepository userRepository;

    /**
     * Demo-mode guard: the demo profile's repositories are session-scoped, and
     * the streaming workers below run on pooled threads with no HTTP session —
     * so in demo mode the request's attributes are propagated to the worker
     * (bound for the turn, always reset after). Null in unit tests (direct
     * construction), which behave as non-demo.
     */
    @Autowired(required = false)
    private Environment environment;

    /**
     * Daemon per-request worker pool: each streaming turn runs on its own
     * thread so the SSE connection stays open while the (potentially
     * multi-round) AI work proceeds.
     */
    private final ExecutorService streamExecutor = new ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "assistant-stream");
                thread.setDaemon(true);
                return thread;
            });

    public AssistantStreamController(AssistantService assistantService,
                                     AssistantTitleService titleService,
                                     UserRepository userRepository) {
        this.assistantService = assistantService;
        this.titleService = titleService;
        this.userRepository = userRepository;
    }

    /**
     * POST /assistant/chat/stream — any authenticated user; same request body
     * as the JSON endpoints plus an optional {@code mode}: "chat" (default),
     * "edit" (needs {@code originalMessage}) or "regenerate" (optional
     * {@code feedback}).
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal UserDetails userDetails,
                             @RequestBody Map<String, String> body,
                             HttpServletRequest request) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database"));

        String mode = body.getOrDefault("mode", "chat");
        Long conversationId = parseConversationId(body.get("conversationId"));
        AgenticAutonomy autonomy = autonomyOf(request);

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);

        executeStreamed(capturedDemoAttributes(), () -> {
            AssistantTraceListener trace = (text, state) -> {
                try {
                    emitter.send(SseEmitter.event().name("step")
                            .data(Map.of("text", text, "state", state), MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    // Client disconnected mid-turn — abort the AI work.
                    throw new IllegalStateException("SSE client disconnected", e);
                }
            };
            try {
                AssistantReply reply = switch (mode) {
                    case "edit" -> assistantService.editAndResend(user,
                            body.get("originalMessage"), message, conversationId, autonomy, trace);
                    case "regenerate" -> assistantService.regenerateMessage(user,
                            message, body.get("feedback"), conversationId, autonomy, trace);
                    default -> assistantService.processMessage(user,
                            message, conversationId, autonomy, trace);
                };
                emitter.send(SseEmitter.event().name("reply").data(reply, MediaType.APPLICATION_JSON));
                // Phase 5 sidebar polish: when this turn triggered the
                // conversation's first summary title, hold the stream open
                // just long enough to deliver it, so the sidebar updates
                // immediately after the reply — no wait for the next poll.
                if (reply.conversationId() != null) {
                    try {
                        boolean generated = titleService
                                .titleFutureFor(reply.conversationId())
                                .get(10, TimeUnit.SECONDS);
                        if (generated) {
                            titleService.latestTitleOf(reply.conversationId())
                                    .ifPresent(title -> {
                                        try {
                                            emitter.send(SseEmitter.event().name("title")
                                                    .data(Map.of("conversationId",
                                                            reply.conversationId(), "title", title),
                                                            MediaType.APPLICATION_JSON));
                                        } catch (Exception ignore) {
                                            // sidebar simply refreshes later
                                        }
                                    });
                        }
                    } catch (Exception ignore) {
                        // title still cooking — sidebar picks it up on next refresh
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("Streaming chat turn failed (mode={}): {}", mode, e.getMessage(), e);
                try {
                    // Same graceful contract as the JSON endpoint: the user
                    // never sees a bare error — a fallback reply instead.
                    emitter.send(SseEmitter.event().name("reply")
                            .data(assistantService.getFallbackReply(user), MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (Exception fatal) {
                    emitter.completeWithError(fatal);
                }
            }
        });

        return emitter;
    }

    /**
     * POST /assistant/actions/{id}/stream — Phase 5: streamed confirm/cancel.
     * Body: {@code {verb: "confirm"|"cancel", conversationId?}}. Emits the
     * same {@code step} / {@code reply} SSE contract as {@code /chat/stream},
     * so the confirmation card shows a live trace while the action runs
     * (or is declined). {@code event:error} signals a gone/foreign action.
     */
    @PostMapping(value = "/actions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAction(@AuthenticationPrincipal UserDetails userDetails,
                                   @PathVariable Long id,
                                   @RequestBody Map<String, String> body,
                                   HttpServletRequest request) {
        String verb = body.getOrDefault("verb", "confirm");
        if (!"confirm".equals(verb) && !"cancel".equals(verb)) {
            throw new IllegalArgumentException("verb must be confirm or cancel");
        }

        User user = userRepository.findByUsername(userDetails.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database"));
        Long conversationId = parseConversationId(body.get("conversationId"));

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);

        executeStreamed(capturedDemoAttributes(), () -> {
            AssistantTraceListener trace = (text, state) -> {
                try {
                    emitter.send(SseEmitter.event().name("step")
                            .data(Map.of("text", text, "state", state), MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    throw new IllegalStateException("SSE client disconnected", e);
                }
            };
            try {
                AssistantReply reply = "cancel".equals(verb)
                        ? assistantService.cancelPendingAction(user, id, conversationId, trace)
                        : assistantService.confirmPendingAction(user, id, conversationId, trace);
                emitter.send(SseEmitter.event().name("reply").data(reply, MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IllegalArgumentException e) {
                // Unknown, foreign or already-resolved action — the JSON
                // endpoints map this to 404; over SSE we tell the client
                // explicitly so it can reset the card.
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("text", "That action is no longer available."),
                                    MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (Exception fatal) {
                    emitter.completeWithError(fatal);
                }
            } catch (Exception e) {
                log.error("Streaming action {} failed: {}", verb, e.getMessage(), e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * The current request's attributes, but only under the {@code demo}
     * profile (null everywhere else, so production workers run exactly as
     * before). Demo workers need them: the demo repositories are
     * session-scoped and the pooled stream threads otherwise have no session.
     */
    private RequestAttributes capturedDemoAttributes() {
        if (!isDemoProfile()) {
            return null;
        }
        try {
            return RequestContextHolder.getRequestAttributes();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** Runs stream work, binding demo request attributes for the turn only. */
    private void executeStreamed(RequestAttributes captured, Runnable work) {
        streamExecutor.execute(() -> {
            boolean bound = false;
            if (captured != null) {
                RequestContextHolder.setRequestAttributes(captured);
                bound = true;
            }
            try {
                work.run();
            } finally {
                // Pooled threads are reused — never leak one turn's session
                // into the next.
                if (bound) {
                    RequestContextHolder.resetRequestAttributes();
                }
            }
        });
    }

    /** True only under the {@code demo} profile (null-safe for unit tests). */
    private boolean isDemoProfile() {
        return environment != null && environment.acceptsProfiles(Profiles.of("demo"));
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
                ? request.getSession(false).getAttribute(AssistantController.AUTONOMY_SESSION_KEY)
                : null;
        return value instanceof AgenticAutonomy mode ? mode : AgenticAutonomy.ALWAYS_CONFIRM;
    }
}

