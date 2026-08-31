package com.cafeerp.assistant;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private static final int MAX_TOOL_ROUNDS = 8;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    private final AssistantMessageRepository messageRepository;
    private final AssistantConversationRepository conversationRepository;
    private final AssistantToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ChatCompletionClient chatCompletionClient;
    private final List<ModelProvider> providers;
    private final DeterministicFallbackHandler fallbackHandler;
    private final AssistantAccessGuard accessGuard;

    public AssistantService(AssistantMessageRepository messageRepository,
                            AssistantConversationRepository conversationRepository,
                            AssistantToolRegistry toolRegistry,
                            ObjectMapper objectMapper,
                            ChatCompletionClient chatCompletionClient,
                            AssistantConfigProperties configProperties,
                            DeterministicFallbackHandler fallbackHandler,
                            AssistantAccessGuard accessGuard) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.chatCompletionClient = chatCompletionClient;
        this.fallbackHandler = fallbackHandler;
        this.accessGuard = accessGuard;

        // Build ordered provider list from configuration
        this.providers = configProperties.getProviders().stream()
                .map(pc -> new ModelProvider(
                        pc.getName(),
                        pc.getBaseUrl(),
                        pc.getApiKeyEnvVar(),
                        pc.getModel(),
                        pc.isSupportsMinTokens()))
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
        return processMessage(user, userMessage, null);
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
        AssistantConversation conversation = resolveConversation(user, conversationId);
        AssistantReply reply = processMessageInConversation(user, userMessage, conversation);
        return new AssistantReply(reply.text(), reply.links(), conversation.getId());
    }

    private AssistantReply processMessageInConversation(User user, String userMessage,
                                                        AssistantConversation conversation) {
        // 1. Persist the user's message — synchronously, in its own committed
        //    transaction, BEFORE any provider work. Chat logging is never
        //    deferred/async, so the turn is queryable the moment this method
        //    returns (and even if a provider later hangs or fails).
        saveMessage(user, AssistantMessageRole.USER, userMessage, conversation);
        touchConversation(conversation, userMessage);

        // 1b. Hard access gate — structural restriction for sensitive topics.
        AssistantAccessGuard.Decision decision = accessGuard.check(user.getRole(), userMessage);
        if (!decision.allowed()) {
            log.info("Assistant access guard blocked restricted topic for user '{}'", user.getUsername());
            AssistantReply denial = new AssistantReply(decision.denialText(), List.of());
            saveMessage(user, AssistantMessageRole.ASSISTANT, denial.text(), conversation);
            return denial;
        }

        // 2. Intelligent routing: check for canonical patterns that should bypass AI entirely.
        //    This is NOT random or threshold-based — it's a rule-based decision:
        //    - Exact order ID lookups (e.g. "order #123", "status of 456") are handled
        //      deterministically because a fixed-format answer is objectively clearer
        //      and safer than a generated one.
        //    These rules are intentionally narrow; everything else goes to AI.
        if (shouldRouteToDeterministicFirst(userMessage, user.getRole())) {
            log.debug("Query matched canonical pattern; routing to deterministic handler first for user '{}'", user.getUsername());
            AssistantReply tier2Reply = fallbackHandler.tryAnswer(userMessage, user.getRole());
            if (tier2Reply != null) {
                saveMessage(user, AssistantMessageRole.ASSISTANT, tier2Reply.text(), conversation);
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

        // System prompt (role-specific)
        messages.add(Map.of(
            "role", "system",
            "content", systemPromptForRole(user.getRole())
        ));

        // Prior conversation (skip the system prompt slot)
        for (AssistantMessage msg : history) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.getRole() == AssistantMessageRole.USER ? "user" : "assistant");
            m.put("content", msg.getContent());
            messages.add(m);
        }

        // 5. Determine role-appropriate tools
        List<Map<String, Object>> tools = toolsForRole(user.getRole());
        Set<String> allowedToolNames = toolRegistry.allowedToolNamesForRole(user.getRole());

        // 6. Try each provider in order — AI path for non-canonical queries
        for (ModelProvider provider : providers) {
            if (!provider.hasApiKey()) {
                log.warn("Skipping provider {}: API key not set (env var {})",
                        provider.name(), provider.apiKeyEnvVar());
                continue;
            }

            AssistantReply reply = tryProvider(provider, messages, tools, allowedToolNames,
                    user, conversation);
            if (reply != null) {
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
            return tier2Reply;
        }

        // 8. Tier 2 also found no match — return unavailable message
        log.warn("Tier 2 fallback also found no match for user '{}'; returning unavailable message", user.getUsername());
        AssistantReply unavailable = fallbackHandler.unavailableMessage(user.getRole());
        saveMessage(user, AssistantMessageRole.ASSISTANT, unavailable.text(), conversation);
        return unavailable;
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
    private boolean shouldRouteToDeterministicFirst(String userMessage, Role role) {
        Set<String> allowedTools = toolRegistry.allowedToolNamesForRole(role);
        // Only route order lookups to deterministic first if the user has access to getOrderStatus
        if (!allowedTools.contains("getOrderStatus")) {
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
                                       AssistantConversation conversation) {
        log.info("Attempting provider: {} (model: {})", provider.name(), provider.model());

        // Deep-copy messages so each provider starts fresh
        List<Map<String, Object>> msgs = deepCopyMessages(messages);

        List<String> firedToolNames = new ArrayList<>();
        Map<String, String> toolNameToUrl = buildSourceUrlMap(user.getRole());

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
                saveMessage(user, AssistantMessageRole.ASSISTANT, finalText, conversation);

                List<SourceLink> links = firedToolNames.stream()
                        .map(name -> {
                            String url = toolNameToUrl.get(name);
                            return url != null ? new SourceLink(labelForTool(name), url) : null;
                        })
                        .filter(l -> l != null)
                        .distinct()
                        .toList();

                return new AssistantReply(finalText, links);
            }

            // Add the assistant's message with tool_calls to the conversation
            msgs.add(message);

            // Execute each tool call — with validation against allowed tool names
            boolean hadValidCall = false;
            for (Map<String, Object> tc : toolCalls) {
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
                firedToolNames.add(name);
                log.debug("Executing tool: {} with args: {}", name, args);

                String result = toolRegistry.execute(name, args);

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
        saveMessage(user, AssistantMessageRole.ASSISTANT, fallback, conversation);

        List<SourceLink> links = firedToolNames.stream()
                .map(name -> {
                    String url = toolNameToUrl.get(name);
                    return url != null ? new SourceLink(labelForTool(name), url) : null;
                })
                .filter(l -> l != null)
                .distinct()
                .toList();

        return new AssistantReply(fallback, links);
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
                + formattingRules;
            case ADMIN ->
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
                + "Voice: human, encouraging, plainspoken — contractions welcome, no corporate jargon. Speak to "
                + "the owner like a trusted colleague: honest about problems, constructive about fixes.\n"
                + formattingRules;
        };
    }

    private List<Map<String, Object>> toolsForRole(Role role) {
        return switch (role) {
            case STAFF -> toolRegistry.toolsForStaff();
            case KITCHEN -> toolRegistry.toolsForKitchen();
            case ADMIN -> toolRegistry.toolsForAdmin();
        };
    }

    private Map<String, String> buildSourceUrlMap(Role role) {
        Map<String, String> map = new HashMap<>();
        map.put("getMenuItems", "/menu");
        map.put("getKitchenQueueSummary", "/kitchen");

        if (role == Role.KITCHEN) {
            map.put("getOrderStatus", "/kitchen");
        } else {
            map.put("getOrderStatus", "/orders/{id}");
        }

        if (role == Role.ADMIN) {
            map.put("getSalesTotals", "/reports");
            map.put("getTopSellingItems", "/reports");
            map.put("getInventoryLevel", "/inventory");
        }

        return map;
    }

    private String labelForTool(String toolName) {
        return switch (toolName) {
            case "getOrderStatus" -> "View Order";
            case "getMenuItems" -> "View Menu";
            case "getSalesTotals" -> "View Sales Report";
            case "getTopSellingItems" -> "View Sales Report";
            case "getInventoryLevel" -> "View Inventory";
            case "getKitchenQueueSummary" -> "View Kitchen Queue";
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

    public record AssistantReply(String text, List<SourceLink> links, Long conversationId) {

        /** Convenience constructor for replies that don't know their thread. */
        public AssistantReply(String text, List<SourceLink> links) {
            this(text, links, null);
        }
    }

    public record SourceLink(String label, String url) {}
}