package com.cafeerp.assistant;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.AbstractMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cafeerp.user.Permission;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private static final int MAX_TOOL_ROUNDS = 16;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    private final AssistantMessageRepository messageRepository;
    private final AssistantConversationRepository conversationRepository;
    private final AssistantToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ChatCompletionClient chatCompletionClient;
    private final List<ModelProvider> providers;
    private final DeterministicFallbackHandler fallbackHandler;
    private final AssistantAccessGuard accessGuard;
    private final AssistantActionLogRepository actionLogRepository;
    /** Phase 5 sidebar polish: AI summary titles for conversations. */
    private final AssistantTitleService titleService;

    public AssistantService(AssistantMessageRepository messageRepository,
                            AssistantConversationRepository conversationRepository,
                            AssistantToolRegistry toolRegistry,
                            ObjectMapper objectMapper,
                            ChatCompletionClient chatCompletionClient,
                            AssistantConfigProperties configProperties,
                            DeterministicFallbackHandler fallbackHandler,
                            AssistantAccessGuard accessGuard,
                            AssistantActionLogRepository actionLogRepository,
                            AssistantTitleService titleService) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.chatCompletionClient = chatCompletionClient;
        this.fallbackHandler = fallbackHandler;
        this.accessGuard = accessGuard;
        this.actionLogRepository = actionLogRepository;
        this.titleService = titleService;

        // Build ordered provider list from configuration
        this.providers = configProperties.getProviders().stream()
                .map(pc -> new ModelProvider(
                        pc.getName(),
                        pc.getBaseUrl(),
                        pc.getApiKeyEnvVar(),
                        pc.getModel(),
                        pc.isSupportsMinTokens(),
                        pc.getTitleModel()))
                .toList();
    }

    /**
     * Process a user message and return the assistant's reply with source links.
     * <p>
     * Order of operations:
     * <ol>
     *   <li>Persist the user's message</li>
     *   <li><b>Hard access gate:</b> {@link AssistantAccessGuard} classifies
     *       restricted topics (sales/finance, payroll, colleague performance)
     *       for non-admin roles. A denied message is answered locally and never
     *       sent to an AI provider or tool handler.</li>
     *   <li><b>Intelligent routing:</b> Check if this query matches a narrow set of
     *       canonical patterns where deterministic pattern-matching produces a
     *       clearer, safer answer than a generated one (e.g. exact order ID lookups).
     *       If so, route directly to the deterministic handler — no AI call needed.</li>
     *   <li>Otherwise: attempt the Groq → Gemini → OpenRouter provider chain for
     *       genuine natural-language understanding with tool grounding.</li>
     *   <li>If every provider fails, fall back to Tier 2 deterministic pattern-matching
     *       as a true fallback.</li>
     *   <li>If Tier 2 also finds no pattern match, return a graceful "unavailable" message
     *       that lists what the user CAN ask about directly.</li>
     * </ol>
     * <p>
     * NOTE: deliberately NOT {@code @Transactional}. AI provider calls can take
     * tens of seconds each (with retries); wrapping them in one transaction would
     * pin a pooled database connection for that whole duration. Each repository
     * save commits independently — fine for chat history. It also avoids the
     * rollback-only trap where an inner service throws, gets caught here, but has
     * already marked the shared transaction rollback-only (previously surfacing as
     * {@code UnexpectedRollbackException} at commit for queries like
     * "status of order 999999").
     */
    public AssistantReply processMessage(User user, String userMessage) {
        return processMessage(user, userMessage, null, AgenticAutonomy.ALWAYS_CONFIRM);
    }

    /**
     * Conversation-aware variant: the turn is persisted into the given
     * conversation (or the user's current one when {@code conversationId} is
     * null, creating one if none exists). See {@link #processMessage(User, String)}
     * for the full flow description.
     * <p>
     * The returned reply always carries the {@code conversationId} the turn
     * landed in, so clients do not need a separate "create conversation"
     * round-trip (whose failure used to silently drop the whole turn).
     */
    public AssistantReply processMessage(User user, String userMessage, Long conversationId) {
        return processMessage(user, userMessage, conversationId, AgenticAutonomy.ALWAYS_CONFIRM);
    }

    /**
     * Agentic variant (Phase 4): {@code autonomy} is the user's per-session
     * autonomy setting, controlling which AI-proposed write actions run
     * automatically and which need explicit confirmation. It lives in the HTTP
     * session, so every new session starts back at {@code ALWAYS_CONFIRM}.
     */
    public AssistantReply processMessage(User user, String userMessage, Long conversationId,
                                         AgenticAutonomy autonomy) {
        return processMessage(user, userMessage, conversationId, autonomy, AssistantTraceListener.NOOP);
    }

    /**
     * Trace-aware variant (Phase 5): the listener receives a human-readable
     * status line for every step the assistant takes (each tool call) as it
     * happens, so the UI can stream "what the AI is doing" while the turn
     * is still in flight.
     */
    public AssistantReply processMessage(User user, String userMessage, Long conversationId,
                                         AgenticAutonomy autonomy, AssistantTraceListener trace) {
        AgenticAutonomy mode = autonomy == null ? AgenticAutonomy.ALWAYS_CONFIRM : autonomy;
        AssistantConversation conversation = resolveConversation(user, conversationId);
        AssistantReply reply = processMessageInConversation(user, userMessage, conversation, mode,
                null, trace == null ? AssistantTraceListener.NOOP : trace);
        return new AssistantReply(reply.text(), reply.links(), conversation.getId(), reply.pendingActions());
    }

    /**
     * Regeneration variant: the user disliked an assistant reply and asked for
     * a new one. The original query is ALREADY in the conversation history, so
     * it is NOT persisted again — instead the model is explicitly told this is
     * a regeneration (with optional user feedback) and must stay true and
     * relevant to the original query. Agentic queries re-run the tool loop, so
     * a regeneration can fetch fresh data rather than rephrase the old answer.
     */
    @Transactional
    public AssistantReply regenerateMessage(User user, String originalMessage, String feedback,
                                            Long conversationId, AgenticAutonomy autonomy) {
        return regenerateMessage(user, originalMessage, feedback, conversationId, autonomy,
                AssistantTraceListener.NOOP);
    }

    /** Trace-aware regeneration variant (Phase 5). */
    @Transactional
    public AssistantReply regenerateMessage(User user, String originalMessage, String feedback,
                                            Long conversationId, AgenticAutonomy autonomy,
                                            AssistantTraceListener trace) {
        AgenticAutonomy mode = autonomy == null ? AgenticAutonomy.ALWAYS_CONFIRM : autonomy;
        AssistantConversation conversation = resolveConversation(user, conversationId);
        // The user is replacing the AI's previous answer(s): delete the
        // assistant replies that the original query produced (the query itself
        // STAYS), then generate a fresh one.
        deleteRepliesToOriginalTurn(conversation, originalMessage);
        AssistantReply reply = processMessageInConversation(user, originalMessage, conversation, mode,
                new Regeneration(feedback), trace == null ? AssistantTraceListener.NOOP : trace);
        return new AssistantReply(reply.text(), reply.links(), conversation.getId(), reply.pendingActions());
    }

    /** Regeneration context: optional user feedback on the previous attempt. */
    public record Regeneration(String feedback) {}

    /**
     * Edit-and-resend: the user edited one of their previous queries. The
     * original query AND the assistant replies it produced are deleted from
     * the thread, then the edited text is processed as a fresh turn (persisted
     * like any normal message). If the original text can no longer be matched
     * (already edited, legacy thread, etc.) the edited message is simply
     * processed as a new turn — the edit never loses the user's input.
     */
    @Transactional
    public AssistantReply editAndResend(User user, String originalMessage, String editedMessage,
                                        Long conversationId, AgenticAutonomy autonomy) {
        return editAndResend(user, originalMessage, editedMessage, conversationId, autonomy,
                AssistantTraceListener.NOOP);
    }

    /** Trace-aware edit-and-resend variant (Phase 5). */
    @Transactional
    public AssistantReply editAndResend(User user, String originalMessage, String editedMessage,
                                        Long conversationId, AgenticAutonomy autonomy,
                                        AssistantTraceListener trace) {
        AgenticAutonomy mode = autonomy == null ? AgenticAutonomy.ALWAYS_CONFIRM : autonomy;
        AssistantConversation conversation = resolveConversation(user, conversationId);
        deleteOriginalTurn(conversation, originalMessage);
        AssistantReply reply = processMessageInConversation(user, editedMessage, conversation, mode,
                null, trace == null ? AssistantTraceListener.NOOP : trace);
        return new AssistantReply(reply.text(), reply.links(), conversation.getId(), reply.pendingActions());
    }

    /**
     * Removes the assistant replies that directly followed the LAST user
     * message matching {@code originalMessage} — the query itself is kept.
     * Stops at the next user message, so later turns are untouched.
     */
    private void deleteRepliesToOriginalTurn(AssistantConversation conversation, String originalMessage) {
        if (conversation.getId() == null || originalMessage == null || originalMessage.isBlank()) {
            return;
        }
        List<AssistantMessage> messages =
                messageRepository.findByConversationOrderByCreatedAtAscIdAsc(conversation);
        int anchor = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AssistantMessage m = messages.get(i);
            if (m.getRole() == AssistantMessageRole.USER && originalMessage.equals(m.getContent())) {
                anchor = i;
                break;
            }
        }
        if (anchor < 0) {
            return;
        }
        for (int i = anchor + 1; i < messages.size(); i++) {
            AssistantMessage m = messages.get(i);
            if (m.getRole() == AssistantMessageRole.USER) {
                break; // a later turn begins — stop deleting
            }
            messageRepository.delete(m);
        }
    }

    /**
     * Removes the LAST user message matching {@code originalMessage} together
     * with every assistant reply that directly followed it (stops at the next
     * user message, so later turns are untouched).
     */
    private void deleteOriginalTurn(AssistantConversation conversation, String originalMessage) {
        if (conversation.getId() == null || originalMessage == null || originalMessage.isBlank()) {
            return;
        }
        List<AssistantMessage> messages =
                messageRepository.findByConversationOrderByCreatedAtAscIdAsc(conversation);
        int anchor = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AssistantMessage m = messages.get(i);
            if (m.getRole() == AssistantMessageRole.USER && originalMessage.equals(m.getContent())) {
                anchor = i;
                break;
            }
        }
        if (anchor < 0) {
            return; // nothing matched — caller still services the edited text
        }
        for (int i = anchor; i < messages.size(); i++) {
            AssistantMessage m = messages.get(i);
            if (i > anchor && m.getRole() == AssistantMessageRole.USER) {
                break; // a later turn begins — stop deleting
            }
            messageRepository.delete(m);
        }
    }

    private AssistantReply processMessageInConversation(User user, String userMessage,
                                                        AssistantConversation conversation,
                                                        AgenticAutonomy autonomy) {
        return processMessageInConversation(user, userMessage, conversation, autonomy, null,
                AssistantTraceListener.NOOP);
    }

    private AssistantReply processMessageInConversation(User user, String userMessage,
                                                        AssistantConversation conversation,
                                                        AgenticAutonomy autonomy,
                                                        Regeneration regen) {
        return processMessageInConversation(user, userMessage, conversation, autonomy, regen,
                AssistantTraceListener.NOOP);
    }

    private AssistantReply processMessageInConversation(User user, String userMessage,
                                                        AssistantConversation conversation,
                                                        AgenticAutonomy autonomy,
                                                        Regeneration regen,
                                                        AssistantTraceListener trace) {
        boolean regenerating = regen != null;

        // 1. Persist the user's message — synchronously, in its own committed
        //    transaction, BEFORE any provider work. Chat logging is never
        //    deferred/async, so the turn is queryable the moment this method
        //    returns (and even if a provider later hangs or fails).
        //    Regeneration skips this: the original query is already persisted.
        if (!regenerating) {
            saveMessage(user, AssistantMessageRole.USER, userMessage, conversation);
            touchConversation(conversation, userMessage);
        } else {
            touchConversation(conversation, null);
        }

        // 1b. Hard access gate — structural restriction for sensitive topics.
        AssistantAccessGuard.Decision decision = accessGuard.check(user.getRole(), userMessage);
        if (!decision.allowed()) {
            log.info("Assistant access guard blocked restricted topic for user '{}'", user.getUsername());
            AssistantReply denial = new AssistantReply(decision.denialText(), List.of());
            saveMessage(user, AssistantMessageRole.ASSISTANT, denial.text(), conversation);
            // First turn of a thread can end here — title it like any other turn.
            titleService.maybeGenerateTitleAsync(conversation, userMessage, denial.text());
            return denial;
        }

        // 2. Intelligent routing: check for canonical patterns that should bypass AI entirely.
        //    This is NOT random or threshold-based — it's a rule-based decision:
        //    - Exact order ID lookups (e.g. "order #123", "status of 456") are handled
        //      deterministically because a fixed-format answer is objectively clearer
        //      and safer than a generated one.
        //    These rules are intentionally narrow; everything else goes to AI.
        //    REGENERATION bypasses this shortcut on purpose: the user is asking for a
        //    different (better) answer, and the deterministic handler would return the
        //    exact same fixed text — feedback could never change anything.
        if (!regenerating && shouldRouteToDeterministicFirst(userMessage, user)) {
            log.debug("Query matched canonical pattern; routing to deterministic handler first for user '{}'", user.getUsername());
            AssistantReply tier2Reply = fallbackHandler.tryAnswer(userMessage, user.getRole());
            if (tier2Reply != null) {
                saveMessage(user, AssistantMessageRole.ASSISTANT, tier2Reply.text(), conversation);
                // Deterministic answers are real first turns too — fire the
                // same async title hook (no-op when already summarized).
                titleService.maybeGenerateTitleAsync(conversation, userMessage, tier2Reply.text());
                return tier2Reply;
            }
            // Deterministic handler declined (shouldn't happen if shouldRouteToDeterministicFirst returned true,
            // but fall through to AI just in case)
        }

        // 3. Load conversation history for AI providers. When the turn belongs
        //    to a conversation, context is scoped to that thread (so switching
        //    conversations does not bleed unrelated questions into the prompt).
        //    Legacy rows without a conversation keep the per-user thread.
        List<AssistantMessage> history = conversation.getId() != null
                ? messageRepository.findByConversationOrderByCreatedAtAscIdAsc(conversation)
                : messageRepository.findByUserOrderByCreatedAtAscIdAsc(user);

        // 4. Build the messages array for the API
        List<Map<String, Object>> messages = new ArrayList<>();

        // Single-round-trip instant titling: when this turn still needs its
        // first summary title (tier-agnostic placeholder check — no role or
        // permission gating), ask the model to append a delimited
        // <title> tag to THIS reply. The tag is parsed and stripped
        // server-side before anything is persisted or shown, so the sidebar
        // title lands at effectively the same moment as the reply itself —
        // no second network round-trip. Plain completion text only; the tag
        // never touches the ERP-knowledge/agentic tool-calling pipeline.
        boolean needsTitle = isUntitledFor(userMessage, conversation);
        String systemPrompt = systemPromptForRole(user, userMessage);
        if (needsTitle) {
            systemPrompt = systemPrompt + "\n" + AssistantTitleService.TITLE_TAG_INSTRUCTION
                    + AmharicLanguageSupport.titleHintFor(userMessage);
        }

        // System prompt (role-specific, plus agentic guidance when the user
        // holds AI_AGENTIC_ACTIONS)
        messages.add(Map.of(
            "role", "system",
            "content", systemPrompt
        ));

        // Prior conversation (skip the system prompt slot)
        for (AssistantMessage msg : history) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.getRole() == AssistantMessageRole.USER ? "user" : "assistant");
            m.put("content", msg.getContent());
            messages.add(m);
        }

        // 4b. Regeneration instruction — an ephemeral (non-persisted) user-role
        //     turn that tells the model this is a redo of its previous answer.
        //     Agentic queries re-run the tool loop above, so the regeneration can
        //     pull fresh data; the instruction keeps the answer anchored to the
        //     ORIGINAL query instead of drifting into meta-commentary.
        if (regenerating) {
            String feedback = regen.feedback();
            String instruction = "The user has asked you to REGENERATE your previous response to their "
                    + "original query above (their last message). This is a redo, not a new question — "
                    + "stay true and relevant to that original query and answer it again. ";
            if (feedback != null && !feedback.isBlank()) {
                instruction += "The user gave this feedback on your previous response — take it into "
                        + "account while staying on-topic: \"" + feedback.trim() + "\" ";
            } else {
                instruction += "No specific feedback was given — aim for a better, more helpful and "
                        + "more accurate response than your previous attempt. ";
            }
            instruction += "Do not mention that you are regenerating; simply answer the original query.";
            messages.add(Map.of("role", "user", "content", instruction));
        }

        // 5. Determine the user's permission-scoped tools (agentic path).
        // Phase 9: the name set is derived from the already-built list —
        // rebuilding the whole tool definitions a second time per turn was
        // pure waste (maps, lists and permission checks, every AI round).
        List<Map<String, Object>> tools = toolRegistry.toolsForUser(user);
        Set<String> allowedToolNames = toolRegistry.toolNamesOf(tools);

        // 6. Try each provider in order — AI path for non-canonical queries
        for (ModelProvider provider : providers) {
            if (!provider.hasApiKey()) {
                log.warn("Skipping provider {}: API key not set (env var {})",
                        provider.name(), provider.apiKeyEnvVar());
                continue;
            }

            AssistantReply reply = tryProvider(provider, messages, tools, allowedToolNames,
                    user, conversation, autonomy, trace, userMessage, needsTitle);
            if (reply != null) {
                // Titling is settled inside tryProvider: the instant
                // single-round-trip <title> tag when this turn needed one,
                // the async second-call fallback otherwise. Nothing to do
                // here (and the in-memory conversation title is stale after
                // an instant apply, so re-checking it here would wrongly
                // fire a redundant second call).
                return reply;
            }
        }

        // 7. All providers failed — try Tier 2 deterministic fallback
        log.warn("All AI providers failed for user '{}'; trying Tier 2 fallback", user.getUsername());
        AssistantReply tier2Reply = fallbackHandler.tryAnswer(userMessage, user.getRole());
        if (tier2Reply != null) {
            log.debug("Tier 2 matched query for user '{}': pattern={}",
                    user.getUsername(), userMessage);
            saveMessage(user, AssistantMessageRole.ASSISTANT, tier2Reply.text(), conversation);
            titleService.maybeGenerateTitleAsync(conversation, userMessage, tier2Reply.text());
            return tier2Reply;
        }

        // 8. Tier 2 also found no match — return unavailable message
        log.warn("Tier 2 fallback also found no match for user '{}'; returning unavailable message", user.getUsername());
        AssistantReply unavailable = fallbackHandler.unavailableMessage(user.getRole(), userMessage);
        saveMessage(user, AssistantMessageRole.ASSISTANT, unavailable.text(), conversation);
        return unavailable;
    }

    // ---------------------------------------------------------------
    //  Phase 4 — agentic actions: audit, confirm, cancel
    // ---------------------------------------------------------------

    /**
     * Executes a pending AI-proposed action after explicit user confirmation.
     * Verifies the action belongs to the caller and is still pending, flips
     * the audit row to EXECUTED (or FAILED), appends the result to the thread
     * and returns it as the reply.
     */
    public AssistantReply confirmPendingAction(User user, Long actionId, Long conversationId) {
        return confirmPendingAction(user, actionId, conversationId, AssistantTraceListener.NOOP);
    }

    /** Trace-aware confirm variant (Phase 5): streams progress into the card. */
    public AssistantReply confirmPendingAction(User user, Long actionId, Long conversationId,
                                               AssistantTraceListener trace) {
        AssistantActionLog action = loadOwnPendingAction(user, actionId);
        AssistantConversation conversation = resolveConversation(user,
                action.getConversationId() != null ? action.getConversationId() : conversationId);

        trace.onStep("Running: " + action.getDescription(), "start");
        String result;
        try {
            result = toolRegistry.execute(action.getTool(), action.getParamsJson(), user);
            action.setStatus(AssistantActionLog.Status.EXECUTED);
            action.setTriggerMode(AssistantActionLog.TriggerMode.USER_CONFIRMED);
        } catch (Exception e) {
            result = "Permission denied: you are not allowed to use " + action.getTool() + ".";
            action.setStatus(AssistantActionLog.Status.FAILED);
            trace.onStep("Couldn't run it — permission denied.", "fail");
        }
        if (AssistantActionLog.Status.EXECUTED.equals(action.getStatus())) {
            trace.onStep(stepDoneText(result), "done");
        }
        action.setResultSummary(result);
        actionLogRepository.save(action);
        log.info("AI action {} by user '{}': tool={}, actionId={}, result={}",
                action.getStatus(), user.getUsername(), action.getTool(), action.getId(), result);

        String text = AssistantActionLog.Status.EXECUTED.equals(action.getStatus())
                ? "✅ Done — " + action.getDescription() + "\n\n" + result
                : result;
        saveMessage(user, AssistantMessageRole.ASSISTANT, text, conversation);
        touchConversation(conversation, null);
        return new AssistantReply(text, List.of(), conversation.getId());
    }

    /**
     * Cancels a pending AI-proposed action. Nothing is executed; the audit
     * row is kept (status CANCELLED) so the trail shows what was proposed
     * and declined.
     */
    public AssistantReply cancelPendingAction(User user, Long actionId, Long conversationId) {
        return cancelPendingAction(user, actionId, conversationId, AssistantTraceListener.NOOP);
    }

    /** Trace-aware cancel variant (Phase 5): streams progress into the card. */
    public AssistantReply cancelPendingAction(User user, Long actionId, Long conversationId,
                                              AssistantTraceListener trace) {
        AssistantActionLog action = loadOwnPendingAction(user, actionId);
        AssistantConversation conversation = resolveConversation(user,
                action.getConversationId() != null ? action.getConversationId() : conversationId);

        trace.onStep("Cancelling: " + action.getDescription(), "start");
        action.setStatus(AssistantActionLog.Status.CANCELLED);
        actionLogRepository.save(action);
        log.info("AI action cancelled by user '{}': tool={}, actionId={}",
                user.getUsername(), action.getTool(), action.getId());
        trace.onStep("Cancelled — nothing was changed.", "done");

        String text = "Cancelled — nothing was changed. (" + action.getDescription() + ")";
        saveMessage(user, AssistantMessageRole.ASSISTANT, text, conversation);
        touchConversation(conversation, null);
        return new AssistantReply(text, List.of(), conversation.getId());
    }

    private AssistantActionLog loadOwnPendingAction(User user, Long actionId) {
        AssistantActionLog action = actionLogRepository.findById(actionId)
                .orElseThrow(() -> new IllegalArgumentException("Pending action not found"));
        if (!action.getUser().getId().equals(user.getId())
                || action.getStatus() != AssistantActionLog.Status.PENDING_CONFIRMATION) {
            // Never execute or cancel another user's action, and never re-run one.
            throw new IllegalArgumentException("Pending action not found");
        }
        return action;
    }

    /** Audit row for a write tool that ran without explicit confirmation. */
    private void auditExecuted(User user, AssistantConversation conversation, String tool,
                               String argsJson, String result,
                               AssistantActionLog.TriggerMode mode) {
        try {
            AssistantActionLog audit = new AssistantActionLog();
            audit.setUser(user);
            audit.setConversationId(conversation.getId());
            audit.setTool(tool);
            audit.setParamsJson(argsJson);
            audit.setDescription(toolRegistry.describeAction(tool, argsJson));
            audit.setStatus(result.startsWith("Permission denied")
                    ? AssistantActionLog.Status.FAILED
                    : AssistantActionLog.Status.EXECUTED);
            audit.setTriggerMode(mode);
            audit.setResultSummary(result);
            actionLogRepository.save(audit);
        } catch (Exception e) {
            // Audit must never break the chat flow.
            log.error("Failed to write AI action audit row for user '{}'", user.getUsername(), e);
        }
    }

    /** Newest audit rows for the admin AI-action log page. */
    @Transactional(readOnly = true)
    public List<AssistantActionLog> getRecentActionLog() {
        return actionLogRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 100));
    }

    /**
     * Pending (awaiting-confirmation) actions for the given user to show in
     * the chat thread. Rows belonging to another conversation are excluded —
     * but rows with no conversation stamp (proposed before a thread existed)
     * are shown so a confirmation card never silently disappears.
     */
    @Transactional(readOnly = true)
    public List<AssistantReply.PendingActionView> getPendingActions(User user, Long conversationId) {
        // Ascending creation order first (the repo query is newest-first), then a
        // stable sort by the model-declared taskOrder so a multi-task turn's
        // confirmation cards always reload in the order the user listed the tasks.
        List<AssistantReply.PendingActionView> views = new ArrayList<>();
        actionLogRepository.findByUserAndStatusOrderByIdDesc(user, AssistantActionLog.Status.PENDING_CONFIRMATION)
                .stream()
                .filter(a -> a.getConversationId() == null
                        || a.getConversationId().equals(conversationId))
                .map(a -> new AssistantReply.PendingActionView(
                        a.getId(), a.getTool(), a.getDescription(), a.getParamsJson()))
                .forEach(views::add);
        java.util.Collections.reverse(views);
        views.sort(java.util.Comparator.comparingInt(this::pendingTaskOrderOf));
        return views;
    }

    /** taskOrder (1-based) parsed from a pending action's params; untagged actions sort by id after tagged ones. */
    private int pendingTaskOrderOf(AssistantReply.PendingActionView view) {
        try {
            Map<String, Object> args = objectMapper.readValue(view.paramsJson() == null ? "{}" : view.paramsJson(),
                    new TypeReference<Map<String, Object>>() {});
            Object order = args.get("taskOrder");
            if (order instanceof Number n && n.intValue() >= 1) {
                return n.intValue();
            }
        } catch (Exception e) {
            // Malformed params — fall through to the id fallback below.
        }
        // Keeps untagged actions in ascending-id order, after all tagged ones.
        return Integer.MAX_VALUE - (1_000_000 - (int) Math.min(view.id() == null ? 0 : view.id(), 1_000_000L));
    }


    /**
     * Rule-based classifier to decide if a query should bypass AI and go straight
     * to the deterministic handler. Returns true only for narrow, unambiguous
     * cases where a fixed-format answer is objectively clearer/safer than generated text.
     * <p>
     * Current rules (intentionally conservative):
     * <ul>
     *   <li>Exact order ID lookups: messages containing a numeric ID pattern like
     *       "order #123", "order 456", "#789", etc.</li>
     * </ul>
     * Everything else returns false and goes through the AI provider chain.
     */
    private boolean shouldRouteToDeterministicFirst(String userMessage, User user) {
        // Phase 9: direct permission check instead of rebuilding the user's
        // entire tool-definition list a second time per turn — getOrderStatus
        // is offered exactly when the user holds ORDER_KITCHEN (see
        // AssistantToolRegistry.toolsForUser), so this is equivalent.
        if (!AgenticPermissions.holds(user, Permission.ORDER_KITCHEN)) {
            return false;
        }
        // Match order-looking queries only: "order #123", "order 456", "#789"
        return DeterministicFallbackHandler.extractOrderId(userMessage).isPresent();
    }

    /**
     * Returns a graceful fallback reply for a given user, persisting it
     * best-effort. Intended for use by the controller's outer catch-all when
     * an unexpected exception occurs anywhere in the chat handler.
     *
     * @param user  the authenticated user
     * @return a graceful AssistantReply that does not expose error details
     */
    public AssistantReply getFallbackReply(User user) {
        AssistantReply reply = fallbackHandler.unavailableMessage(user.getRole());
        try {
            // Attach to the user's current conversation so even a turn that
            // blew up mid-flight is queryable in history immediately.
            AssistantConversation conversation = resolveConversation(user, null);
            saveMessage(user, AssistantMessageRole.ASSISTANT, reply.text(), conversation);
            touchConversation(conversation, null);
        } catch (Exception e) {
            log.error("Failed to persist fallback assistant message for user '{}'", user.getUsername(), e);
        }
        return reply;
    }

    /**
     * Try a single model provider's tool-calling loop. Returns null if the provider
     * fails and the caller should try the next one.
     */
    @SuppressWarnings("unchecked")
    private AssistantReply tryProvider(ModelProvider provider,
                                       List<Map<String, Object>> messages,
                                       List<Map<String, Object>> tools,
                                       Set<String> allowedToolNames,
                                       User user,
                                       AssistantConversation conversation,
                                       AgenticAutonomy autonomy,
                                       AssistantTraceListener trace,
                                       String userMessage,
                                       boolean needsTitle) {
        log.info("Attempting provider: {} (model: {})", provider.name(), provider.model());

        // Deep-copy messages so each provider starts fresh
        List<Map<String, Object>> msgs = deepCopyMessages(messages);

        // Per-provider Amharic booster: the free models behind Groq, Gemini
        // and OpenRouter do not share the same out-of-the-box fluency, so each
        // gets its own few-line reinforcement appended to the system message
        // of ITS copy only. English turns yield an empty booster — the English
        // path (and its latency characteristics: one small string concat) is
        // unaffected.
        String booster = AmharicLanguageSupport.boosterFor(
                provider.name(), AmharicLanguageSupport.detect(userMessage));
        if (!booster.isEmpty() && !msgs.isEmpty() && "system".equals(msgs.get(0).get("role"))) {
            Map<String, Object> system = new HashMap<>(msgs.get(0));
            system.put("content", system.get("content") + "\n" + booster);
            msgs.set(0, system);
        }

        List<String> firedToolNames = new ArrayList<>();
        Map<String, String> toolNameToUrl = buildSourceUrlMap(user);
        // Write actions queued for the user's confirmation (Phase 4). There
        // can be SEVERAL in one turn — a single message may request multiple
        // tasks, so every confirmation-requiring call gets its own pending
        // card instead of aborting the rest of the turn.
        List<AssistantReply.PendingActionView> pendingActions = new ArrayList<>();
        StringBuilder pendingSummary = new StringBuilder();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            Map<String, Object> response = callProvider(provider, msgs, tools);
            if (response == null) {
                log.warn("Provider {} failed (null response), moving to next", provider.name());
                return null;
            }

            Map<String, Object> choice = ((List<Map<String, Object>>) response.get("choices")).get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");

            String content = (String) message.get("content");
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

            if (toolCalls == null || toolCalls.isEmpty()) {
                // Final response — persist and return
                String finalText = content != null ? content : "";
                // Instant title: strip any <title> tag before anyone sees
                // or stores the text (defense in depth: the tag is only
                // requested on first turns, but a stray tag must never leak
                // to the user even if the model emits one later). The
                // sanitized title is applied only when this turn still owes
                // the conversation its first summary.
                String instantTitle = null;
                AssistantTitleService.InlineTitle inline =
                        AssistantTitleService.extractInlineTitle(finalText);
                if (inline != null) {
                    finalText = inline.visibleText();
                    if (needsTitle) {
                        instantTitle = inline.title();
                    }
                }
                finalText = appendPendingActionsNote(finalText, pendingActions, pendingSummary);
                saveMessage(user, AssistantMessageRole.ASSISTANT, finalText, conversation);

                List<SourceLink> links = firedToolNames.stream()
                        .map(name -> {
                            String url = toolNameToUrl.get(name);
                            return url != null ? new SourceLink(labelForTool(name), url) : null;
                        })
                        .filter(l -> l != null)
                        .distinct()
                        .toList();

                AssistantReply done = new AssistantReply(finalText, links, conversation.getId(), pendingActions);
                settleTitle(conversation, userMessage, done.text(), instantTitle);
                return done;
            }

            // Add the assistant's message with tool_calls to the conversation
            msgs.add(message);

            // Deterministic left-to-right card ordering: sort this round's
            // tool calls by the model-declared `taskOrder` (the task's
            // 1-based position in the user's message) before executing.
            // The sort is stable — untagged calls keep their original
            // relative order — so pending actions (and therefore the
            // confirmation cards, both live and on reload) always appear
            // exactly in the order the user listed their tasks.
            List<Map<String, Object>> orderedCalls = orderToolCallsByTaskOrder(toolCalls);

            // Execute each tool call — with validation against allowed tool names
            boolean hadValidCall = false;
            for (Map<String, Object> tc : orderedCalls) {
                String id = (String) tc.get("id");
                Map<String, Object> function = (Map<String, Object>) tc.get("function");
                String name = (String) function.get("name");
                String args = (String) function.get("arguments");

                // SECURITY: Validate tool name against the request's allowed set
                if (!allowedToolNames.contains(name)) {
                    log.warn("Provider {} called tool '{}' which is NOT in the allowed set {} — rejecting",
                            provider.name(), name, allowedToolNames);
                    Map<String, Object> toolMessage = new HashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", id);
                    toolMessage.put("content", "Error: The tool '" + name
                            + "' is not available. You may only use these tools: "
                            + String.join(", ", allowedToolNames)
                            + ". Please correct your response and try again.");
                    msgs.add(toolMessage);
                    continue;
                }

                hadValidCall = true;
                log.debug("Executing tool: {} with args: {}", name, args);

                // Phase 4 — write tools: confirmation gate + audit logging.
                if (toolRegistry.isWriteTool(name)) {
                    if (toolRegistry.requiresConfirmation(name, autonomy)) {
                        // Persist the proposed action as a pending confirmation,
                        // surfaced to the user as an action card. Nothing is
                        // executed until they confirm via the UI endpoint.
                        // IMPORTANT: we do NOT return here — the remaining
                        // tool calls in this round and further rounds (the
                        // user's other tasks) still run, so a single message
                        // can accomplish as many tasks as it requests.
                        AssistantActionLog pending = new AssistantActionLog();
                        pending.setUser(user);
                        pending.setConversationId(conversation.getId());
                        pending.setTool(name);
                        pending.setParamsJson(args);
                        pending.setDescription(toolRegistry.describeAction(name, args));
                        pending.setStatus(AssistantActionLog.Status.PENDING_CONFIRMATION);
                        actionLogRepository.save(pending);
                        log.info("AI action pending confirmation: user='{}', tool={}, actionId={}",
                                user.getUsername(), name, pending.getId());

                        pendingActions.add(new AssistantReply.PendingActionView(
                                pending.getId(), name, pending.getDescription(), args));
                        pendingSummary.append("\n- ").append(pending.getDescription());

                        // Phase 5 — surface the proposed action in the live trace
                        trace.onStep("Preparing: " + pending.getDescription(), "start");
                        trace.onStep("Queued for your approval — nothing runs until you confirm.", "done");

                        // Feed the model a tool result so it knows the action
                        // is queued (not executed) and can carry on with any
                        // remaining independent tasks in the same turn.
                        Map<String, Object> pendingToolMessage = new HashMap<>();
                        pendingToolMessage.put("role", "tool");
                        pendingToolMessage.put("tool_call_id", id);
                        pendingToolMessage.put("content",
                                "Queued for user confirmation (action id " + pending.getId()
                                        + "). It has NOT been executed yet. If the user's request includes MORE "
                                        + "tasks, IMMEDIATELY issue their tool calls too (one call per task, with "
                                        + "the correct taskOrder) — do NOT wait for the user to ask again and do NOT "
                                        + "stop until every task they mentioned has its own call. Do NOT repeat this "
                                        + "call and do NOT make calls that depend on this one's outcome.");
                        msgs.add(pendingToolMessage);
                        continue;
                    }

                    // Low-risk auto execution (only reachable in AUTO_LOW_RISK mode)
                    trace.onStep(stepStartText(name, args), "start");
                    String autoResult = toolRegistry.execute(name, args, user);
                    trace.onStep(stepDoneText(autoResult), "done");
                    auditExecuted(user, conversation, name, args, autoResult,
                            AssistantActionLog.TriggerMode.AUTO_EXECUTED);
                    firedToolNames.add(name);

                    Map<String, Object> autoToolMessage = new HashMap<>();
                    autoToolMessage.put("role", "tool");
                    autoToolMessage.put("tool_call_id", id);
                    autoToolMessage.put("content", autoResult);
                    msgs.add(autoToolMessage);
                    continue;
                }

                firedToolNames.add(name);
                trace.onStep(stepStartText(name, args), "start");
                String result = toolRegistry.execute(name, args, user);
                trace.onStep(stepDoneText(result), "done");

                Map<String, Object> toolMessage = new HashMap<>();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", id);
                toolMessage.put("content", result);
                msgs.add(toolMessage);
            }

            if (!hadValidCall) {
                log.warn("Provider {}: all tool calls in round {} were rejected", provider.name(), round);
            }
        }

        // Cap reached — graceful fallback (don't fail the provider, return a polite message)
        log.warn("Provider {} hit {} round cap", provider.name(), MAX_TOOL_ROUNDS);
        String fallback = "I've gathered some information but need more detail to give a complete answer. "
                + "Could you rephrase or narrow down your question?";
        String fallbackInstantTitle = null;
        AssistantTitleService.InlineTitle fallbackInline =
                AssistantTitleService.extractInlineTitle(fallback);
        if (fallbackInline != null) {
            fallback = fallbackInline.visibleText();
            if (needsTitle) {
                fallbackInstantTitle = fallbackInline.title();
            }
        }
        fallback = appendPendingActionsNote(fallback, pendingActions, pendingSummary);
        saveMessage(user, AssistantMessageRole.ASSISTANT, fallback, conversation);

        List<SourceLink> links = firedToolNames.stream()
                .map(name -> {
                    String url = toolNameToUrl.get(name);
                    return url != null ? new SourceLink(labelForTool(name), url) : null;
                })
                .filter(l -> l != null)
                .distinct()
                .toList();

        AssistantReply capped = new AssistantReply(fallback, links, conversation.getId(), pendingActions);
        settleTitle(conversation, userMessage, capped.text(), fallbackInstantTitle);
        return capped;
    }

    /**
     * Human-readable status line for a tool call about to run (Phase 5).
     * Plain language only — no raw tool names, no parameter blobs. Where the
     * arguments name a concrete thing (an order id, an item) it is echoed so
     * the line reads like "Looking up order #482…".
     */
    private String stepStartText(String tool, String argsJson) {
        Map<String, Object> args = parseArgsQuietly(argsJson);
        Object orderId = args.get("orderId");
        Object itemName = args.get("itemName");
        Object username = args.get("username");
        Object range = args.get("range");
        return switch (tool) {
            case "getOrderStatus" -> "Looking up order #" + safe(orderId, "…") + "…";
            case "getOrderHistory" -> "Checking recent orders" + (username != null ? " for " + username : "") + "…";
            case "getMenuItems" -> "Checking the menu…";
            case "getInventoryLevel" -> "Checking inventory for " + safe(itemName, "stock") + "…";
            case "getSalesTotals" -> "Tallying sales" + (range != null ? " for " + range : "") + "…";
            case "getTopSellingItems" -> "Finding the best-sellers…";
            case "getKitchenQueueSummary" -> "Checking the kitchen queue…";
            case "getUserLoginHistory" -> "Checking sign-in history…";
            case "getUserSessionActivity" -> "Checking account activity…";
            case "createOrder" -> "Preparing the new order…";
            case "updateOrderStatus" -> "Updating order #" + safe(orderId, "…") + "…";
            case "updateInventory" -> "Updating stock for " + safe(itemName, "item") + "…";
            case "updateInventoryAlert" -> "Adjusting stock alerts for " + safe(itemName, "item") + "…";
            case "updateMenuItem" -> "Updating menu item " + safe(itemName, "…") + "…";
            default -> "Working on it…";
        };
    }

    /**
     * Human-readable completion line for a finished tool call: "Done — …"
     * followed by the first line of the (usually short, textual) tool result,
     * so the user sees the outcome without raw payloads.
     */
    private static String stepDoneText(String result) {
        String line = result == null ? "" : result.strip().lines().findFirst().orElse("");
        if (line.length() > 120) {
            line = line.substring(0, 117) + "…";
        }
        return line.isEmpty() ? "Done." : "Done — " + line;
    }

    private Map<String, Object> parseArgsQuietly(String argsJson) {
        try {
            return objectMapper.readValue(argsJson == null ? "{}" : argsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String safe(Object value, String fallback) {
        String s = value == null ? null : String.valueOf(value).trim();
        return (s == null || s.isEmpty()) ? fallback : s;
    }

    /**
     * When the turn queued write actions for confirmation, make sure the
     * persisted reply tells the user what awaits approval (the cards render
     * from the pendingActions payload, but the text must stand alone too).
     */
    private static String appendPendingActionsNote(String text,
                                                   List<AssistantReply.PendingActionView> pendingActions,
                                                   StringBuilder pendingSummary) {
        if (pendingActions == null || pendingActions.isEmpty()) {
            return text;
        }
        return text + "\n\n**Awaiting your approval (" + pendingActions.size()
                + (pendingActions.size() == 1 ? " action):**" : " actions):**")
                + pendingSummary
                + "\n\nReview the cards above and confirm to run them, or cancel any that isn't right.";
    }

    /**
     * Returns the full message thread for a given user (read-only), legacy
     * single-thread view. Ordered by createdAt then id so a question and its
     * answer persisted in the same microsecond still sort deterministically.
     */
    @Transactional(readOnly = true)
    public List<AssistantMessage> getHistory(User user) {
        return messageRepository.findByUserOrderByCreatedAtAscIdAsc(user);
    }

    /**
     * Returns users who have assistant messages, most recently active first.
     */
    @Transactional(readOnly = true)
    public List<User> getUsersWithMessages() {
        return messageRepository.findUsersWithMessagesOrderByMostRecent();
    }

    // ---------------------------------------------------------------
    //  Conversations (chat history UI)
    // ---------------------------------------------------------------

    /**
     * Resolves the conversation a chat turn should attach to. When
     * {@code conversationId} is given it must exist, belong to the user, and
     * not be archived — otherwise a 404-mapped IllegalArgumentException is
     * thrown (the UI only ever passes its own conversation ids). When null,
     * the user's most recent non-archived conversation is reused, creating a
     * fresh one if none exists (legacy clients / first ever message).
     */
    @Transactional
    public AssistantConversation resolveConversation(User user, Long conversationId) {
        if (conversationId != null) {
            AssistantConversation conversation = conversationRepository.findById(conversationId)
                    .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
            if (!conversation.getUser().getId().equals(user.getId())) {
                // Never leak or mutate another user's thread.
                throw new IllegalArgumentException("Conversation not found");
            }
            return conversation;
        }
        return conversationRepository
                .findFirstByUserAndArchivedAtIsNullOrderByLastActivityAtDesc(user)
                .orElseGet(() -> conversationRepository.save(new AssistantConversation(user)));
    }

    /** Starts a fresh conversation for the user ("New chat" button). */
    @Transactional
    public AssistantConversation createConversation(User user) {
        AssistantConversation conversation = conversationRepository.save(new AssistantConversation(user));
        log.debug("New assistant conversation id={} for user '{}'", conversation.getId(), user.getUsername());
        return conversation;
    }

    /** Default history list for the sidebar/overlay — newest activity first. */
    @Transactional(readOnly = true)
    public List<AssistantConversation> listConversations(User user, boolean archived) {
        return archived
                ? conversationRepository.findByUserAndArchivedAtIsNotNullOrderByLastActivityAtDesc(user)
                : conversationRepository.findByUserAndArchivedAtIsNullOrderByLastActivityAtDesc(user);
    }

    /** Messages of one conversation; enforces ownership. */
    @Transactional(readOnly = true)
    public List<AssistantMessage> getConversationMessages(User user, Long conversationId) {
        AssistantConversation conversation = resolveConversation(user, conversationId);
        return messageRepository.findByConversationOrderByCreatedAtAscIdAsc(conversation);
    }

    // ---------------------------------------------------------------
    //  Chat persistence helpers
    // ---------------------------------------------------------------

    /**
     * Persists one chat message synchronously. Every call site in the chat
     * flow routes through here so a turn's user message and assistant reply
     * always land in the same conversation and commit immediately.
     */
    private void saveMessage(User user, AssistantMessageRole role, String content,
                             AssistantConversation conversation) {
        messageRepository.save(new AssistantMessage(user, role, content, conversation));
    }

    /**
     * Whether this turn still owes the conversation its first summary title:
     * the thread carries no title yet or still carries the raw derived
     * placeholder of THIS user message. Deliberately tier-agnostic — no
     * role or permission check; every tier titles identically.
     */
    private static boolean isUntitledFor(String userMessage, AssistantConversation conversation) {
        if (conversation == null || conversation.getId() == null
                || userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String current = conversation.getTitle();
        return current == null || current.equals(AssistantConversation.deriveTitle(userMessage));
    }

    /**
     * Settles the sidebar title for an AI-turn reply: the instant
     * single-round-trip title when the model produced one (synchronous, so
     * the title is already stored when the reply is confirmed delivered),
     * otherwise the async second-call fallback (no-op when already
     * summarized or when the instant apply just landed).
     */
    private void settleTitle(AssistantConversation conversation, String userMessage,
                             String replyText, String instantTitle) {
        if (instantTitle != null
                && titleService.applyTitleNow(conversation.getId(), instantTitle)) {
            return;
        }
        titleService.maybeGenerateTitleAsync(conversation, userMessage, replyText);
    }

    /**
     * Bumps the conversation's activity timestamp and derives its title from
     * the first user message. Called once per user turn.
     */
    private void touchConversation(AssistantConversation conversation, String firstUserMessage) {
        if (conversation == null || conversation.getId() == null) {
            return; // legacy/edge paths without a conversation
        }
        if (conversation.getTitle() == null && firstUserMessage != null) {
            conversation.setTitle(AssistantConversation.deriveTitle(firstUserMessage));
        }
        conversation.setLastActivityAt(LocalDateTime.now(ZoneOffset.UTC));
        conversationRepository.save(conversation);
    }

    // ---------------------------------------------------------------
    //  Private helpers
    // ---------------------------------------------------------------

    private String systemPromptForRole(Role role) {
        return systemPromptForRole(role, false);
    }

    /**
     * Role prompt plus, for agentic users (AI_AGENTIC_ACTIONS), guidance on
     * how write tools behave: order-impacting actions record a pending
     * confirmation the user must approve in the UI.
     */
    private String systemPromptForRole(User user) {
        boolean agentic = AgenticPermissions.isAgentic(user);
        return systemPromptForRole(user.getRole(), agentic);
    }

    /**
     * Turn-aware variant: layers the native Amharic voice directive for this
     * specific user message on top of the role brief. Pure English turns get
     * an empty directive, so the English path is exactly what it was before.
     */
    private String systemPromptForRole(User user, String userMessage) {
        String base = systemPromptForRole(user);
        String directive = AmharicLanguageSupport.directiveFor(userMessage);
        return directive.isEmpty() ? base : base + "\n" + directive;
    }

    private String systemPromptForRole(Role role, boolean agentic) {
        String languageRules =
                "Language — follow it exactly: you are fluent in Amharic as well as English, including "
                + "Amharic written in Latin letters (transliterated Amharic — the common way people type "
                + "Amharic on phones, e.g. \"selam, dehna neh?\"). Understand all three equally well. Always "
                + "match the user's script: Latin-transliterated Amharic → reply in Latin-transliterated "
                + "Amharic; Ge'ez script (e.g. ሰላም, ደህና ነህ؟) → reply in Ge'ez script; English → reply in "
                + "English. Never switch scripts unless the user explicitly asks. ERP data stays exactly as "
                + "returned — never transliterate menu item names, statuses, or anything going into tool calls.\n";
        String formattingRules =
                "Formatting rules — follow them exactly: respond in clean, professional Markdown that renders "
                + "tightly and scans quickly. Use compact short paragraphs separated by AT MOST one blank line — "
                + "never two or more consecutive blank lines anywhere. Use **bold** for key numbers and labels, "
                + "real Markdown bullet lists only where a list genuinely helps (items on consecutive lines, no "
                + "blank lines between items), and Markdown tables (| col | col |) for structured data like menu "
                + "items, inventory levels, or sales breakdowns — not walls of plain text. "
                + "Emoji policy: almost never. Most responses must contain zero emoji. Include one emoji only when "
                + "it is semantically tied to the content itself (e.g. ✅ confirming a specific completed action, "
                + "⚠️ flagging a genuine warning such as low stock, 🥐 when naming a specific menu item or "
                + "category). Never use emoji decoratively, never more than one per response, never in headings.";

        return switch (role) {
            case STAFF, KITCHEN ->
                // Shift-floor buddy: practical, warm, grounded in real queue/menu/order data.
                "You are the shift-floor buddy at this cafe — think of a friendly senior coworker who knows "
                + "the place inside out and loves helping people get through their shift smoothly. You can "
                + "pull live order status, menu items, prices and availability, and the kitchen queue "
                + "(PENDING/PREPARING/READY counts) using your tools — do so whenever a question touches on "
                + "what's happening right now instead of answering from memory. Only state facts you got from "
                + "tool calls; if you don't have data for something, say so plainly.\n"
                + "When someone asks how to get through their shift faster or handle a rush, give practical, "
                + "down-to-earth advice a coworker would actually use: batch similar drinks, fire tickets in "
                + "order, grab the ready orders before starting new ones, keep communication with the kitchen "
                + "clear and stay upfront about delays. Ground it in the real queue numbers you pulled, then "
                + "suggest one or two concrete next moves rather than a lecture.\n"
                + "Voice: human and approachable — contractions welcome, short sentences, light warmth, no "
                + "corporate filler. Address them like a colleague at work, not a customer.\n"
                + "Boundaries: some things are manager-only territory (sales figures, revenue, profit margins, "
                + "payroll, or how individual coworkers are performing). Never guess, estimate or hint at those "
                + "even playfully or hypothetically — say it's manager territory and pivot to what you can help "
                + "with. Never discuss what tools other roles have.\n"
                + languageRules
                + formattingRules;
            case ADMIN, SUPER_ADMIN ->
                // In-house business analyst: pulls real data, interprets it, recommends.
                "You are this cafe's resident business analyst — a sharp, warm coworker who happens to love "
                + "numbers and turning them into decisions. Managers come to you with questions like \"how do "
                + "we increase sales?\", \"what's not working?\" or \"are we stocked for the weekend?\"\n"
                + "Your method, every analytical question: (1) pull the relevant data with your tools first — "
                + "sales totals and order counts for a sensible period, top sellers, inventory levels, and the "
                + "kitchen queue when throughput matters; (2) interpret what you see — call out trends, gaps, "
                + "outliers and what they likely mean for THIS cafe; (3) recommend — finish with 2-3 concrete, "
                + "prioritized actions an owner could take this week, each tied to the numbers you just cited. "
                + "Do not stop at reciting figures; the value is the judgment around them.\n"
                + "Ground rules: only state facts returned by tool calls you actually made. If data is missing "
                + "or the period is too small to be meaningful, say so plainly and note the caveat before "
                + "reasoning anyway. Never estimate, invent numbers, or import outside market stats. If intent "
                + "is ambiguous, give your best analysis from available data and ask ONE short follow-up "
                + "question. Never discuss what tools other roles have.\n"
                + languageRules
                + "Voice: human, encouraging, plainspoken — contractions welcome, no corporate jargon. Speak to "
                + "the owner like a trusted colleague: honest about problems, constructive about fixes.\n"
                + (agentic
                    ? "Agentic actions: you can create orders, change order statuses, update inventory counts, "
                    + "adjust low-stock alert thresholds and stock tracking, and update menu item details "
                    + "(price, availability, name) with your write tools. Use them when the user clearly asks "
                    + "for the change — never on a guess. "
                    + "Order-impacting actions (createOrder, updateOrderStatus) and menu edits (updateMenuItem) "
                    + "are recorded as a pending action the user must approve in the chat UI, so tell them "
                    + "plainly what you are about to do and why. Inventory updates and alert-threshold changes "
                    + "may run automatically depending on the user's autonomy setting. You must NEVER attempt "
                    + "to create, delete or modify user accounts, roles or permissions, and never delete data — "
                    + "those actions do not exist for you.\n"
                    + "Task handling (CRITICAL): when a message asks for several tasks, you MUST cover EVERY task "
                    + "the user mentioned — one tool call per task, ALL issued before you write your final answer. "
                    + "Issue them in the SAME order the tasks appear in the user's message (read it left to right) "
                    + "and set each call's taskOrder parameter to that task's 1-based position in the message "
                    + "(first-mentioned task = taskOrder 1, second = 2, and so on). NEVER skip a task, NEVER stop "
                    + "after the first one, and NEVER ask the user to prompt you to continue — queue every "
                    + "remaining task immediately. Each task gets its own tool call so it gets its own "
                    + "confirmation card, and the cards appear in exactly the order the user listed the tasks. "
                    + "If a later task DEPENDS on an earlier one that is still awaiting the user's confirmation, "
                    + "do not guess its outcome: tell the user to confirm the earlier card first.\n"
                    : "")
                + formattingRules;
        };
    }

    /**
     * Orders a round's tool calls by the model-declared {@code taskOrder}
     * (the task's 1-based position in the user's message) so confirmation
     * cards render exactly in the order the user listed their tasks.
     * Stable: calls without a usable {@code taskOrder} keep their original
     * relative order, after any tagged calls.
     */
    private List<Map<String, Object>> orderToolCallsByTaskOrder(List<Map<String, Object>> toolCalls) {
        if (toolCalls.size() < 2) {
            return toolCalls;
        }
        List<Map.Entry<Integer, Map<String, Object>>> indexed = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            indexed.add(new AbstractMap.SimpleImmutableEntry<>(i, toolCalls.get(i)));
        }
        // List.sort is stable (TimSort) — ties keep their original order.
        indexed.sort((a, b) -> Integer.compare(taskOrderOf(a.getValue(), a.getKey()),
                taskOrderOf(b.getValue(), b.getKey())));
        List<Map<String, Object>> ordered = new ArrayList<>(toolCalls.size());
        for (Map.Entry<Integer, Map<String, Object>> e : indexed) {
            ordered.add(e.getValue());
        }
        if (log.isDebugEnabled() && !ordered.equals(toolCalls)) {
            log.debug("Reordered {} tool calls by taskOrder to match the user's stated task sequence",
                    toolCalls.size());
        }
        return ordered;
    }

    /** Extracts the model-declared taskOrder (1-based) or a fallback that preserves the original position. */
    private int taskOrderOf(Map<String, Object> toolCall, int originalIndex) {
        try {
            Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
            String argsJson = (String) function.get("arguments");
            Map<String, Object> args = objectMapper.readValue(argsJson == null ? "{}" : argsJson,
                    new TypeReference<Map<String, Object>>() {});
            Object order = args.get("taskOrder");
            if (order instanceof Number n && n.intValue() >= 1) {
                return n.intValue();
            }
        } catch (Exception e) {
            // Malformed arguments — fall through to the positional fallback.
        }
        // Untagged calls sort after all tagged ones, in their original order.
        return Integer.MAX_VALUE - (1_000_000 - originalIndex);
    }

    /** Source links for the permission-scoped agentic tool set. */
    private Map<String, String> buildSourceUrlMap(User user) {
        Map<String, String> map = new HashMap<>();
        map.put("getMenuItems", "/menu");
        map.put("getKitchenQueueSummary", "/kitchen");
        map.put("getOrderStatus", "/orders/{id}");
        map.put("getOrderHistory", "/orders");

        if (AgenticPermissions.holds(user, Permission.REPORT)) {
            map.put("getSalesTotals", "/reports");
            map.put("getTopSellingItems", "/reports");
        }
        if (AgenticPermissions.holds(user, Permission.INVENTORY)) {
            map.put("getInventoryLevel", "/inventory");
            map.put("updateInventory", "/inventory");
            map.put("updateInventoryAlert", "/inventory");
        }
        if (AgenticPermissions.holds(user, Permission.MENU)) {
            map.put("updateMenuItem", "/menu");
        }
        if (AgenticPermissions.holds(user, Permission.ORDER_KITCHEN)) {
            map.put("createOrder", "/orders");
            map.put("updateOrderStatus", "/orders/{id}");
        }
        if (AgenticPermissions.holds(user, Permission.USER_MANAGEMENT)) {
            map.put("getUserLoginHistory", "/admin/users");
            map.put("getUserSessionActivity", "/admin/users");
        }
        return map;
    }

    private String labelForTool(String toolName) {
        return switch (toolName) {
            case "getOrderStatus" -> "View Order";
            case "getOrderHistory" -> "View Orders";
            case "getMenuItems" -> "View Menu";
            case "getSalesTotals" -> "View Sales Report";
            case "getTopSellingItems" -> "View Sales Report";
            case "getInventoryLevel" -> "View Inventory";
            case "getKitchenQueueSummary" -> "View Kitchen Queue";
            case "getUserLoginHistory", "getUserSessionActivity" -> "View Users";
            case "createOrder" -> "View Orders";
            case "updateOrderStatus" -> "View Order";
            case "updateInventory" -> "View Inventory";
            default -> "View Details";
        };
    }

    /**
     * Call a model provider's /chat/completions endpoint with retry logic.
     * Returns null if the provider fails (to trigger failover).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callProvider(ModelProvider provider,
                                             List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools) {
        String apiKey = provider.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.error("{} API key not set (env var {})", provider.name(), provider.apiKeyEnvVar());
            return null;
        }

        // Retry once for transient failures
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("model", provider.model());
                body.put("messages", messages);
                body.put("tools", tools);
                body.put("tool_choice", "auto");
                if (provider.supportsMinTokens()) {
                    body.put("min_tokens", 0);
                }

                String jsonBody = objectMapper.writeValueAsString(body);

                ChatCompletionClient.Result result =
                        chatCompletionClient.post(provider.url(), apiKey, jsonBody);

                int status = result.status();

                if (status == 429) {
                    log.warn("{} rate-limited (429) on attempt {}; retrying after {}ms",
                            provider.name(), attempt + 1, RETRY_DELAY.toMillis());
                    if (attempt == 0) {
                        Thread.sleep(RETRY_DELAY.toMillis());
                        continue;
                    }
                    return null;
                }

                if (status >= 500) {
                    log.warn("{} server error ({}): attempt {}; body={}",
                            provider.name(), status, attempt + 1, result.body());
                    if (attempt == 0) {
                        Thread.sleep(RETRY_DELAY.toMillis());
                        continue;
                    }
                    return null;
                }

                if (status >= 400) {
                    log.warn("{} API error: status={}, body={}",
                            provider.name(), status, result.body());
                    return null;
                }

                return objectMapper.readValue(result.body(),
                        new TypeReference<Map<String, Object>>() {});

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("{} call interrupted", provider.name());
                return null;
            } catch (Exception e) {
                log.error("{} API call failed on attempt {}", provider.name(), attempt + 1, e);
                if (attempt == 0) {
                    try {
                        Thread.sleep(RETRY_DELAY.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                return null;
            }
        }

        return null;
    }

    /**
     * Deep-copy a list of message maps so each provider gets an independent copy.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deepCopyMessages(List<Map<String, Object>> original) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> msg : original) {
            copy.add(new HashMap<>(msg));
        }
        return copy;
    }

    // ---------------------------------------------------------------
    //  Value objects
    // ---------------------------------------------------------------

    public record AssistantReply(String text, List<SourceLink> links, Long conversationId,
                                 List<PendingActionView> pendingActions) {

        /** Convenience constructor for replies that don't know their thread. */
        public AssistantReply(String text, List<SourceLink> links) {
            this(text, links, null, List.of());
        }

        /** Convenience constructor for replies with a known thread. */
        public AssistantReply(String text, List<SourceLink> links, Long conversationId) {
            this(text, links, conversationId, List.of());
        }

        /**
         * Set when the AI proposed write actions that require the user's
         * explicit confirmation (Phase 4 confirmation flow). There may be
         * SEVERAL — a single message can request multiple tasks, and each
         * confirmation-requiring action gets its own card.
         */
        public record PendingActionView(Long id, String tool, String description, String paramsJson) {}
    }

    public record SourceLink(String label, String url) {}
}