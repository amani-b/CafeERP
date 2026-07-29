package com.cafeerp.assistant;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    private static final int MAX_TOOL_ROUNDS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    private final AssistantMessageRepository messageRepository;
    private final AssistantToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final List<ModelProvider> providers;
    private final DeterministicFallbackHandler fallbackHandler;

    public AssistantService(AssistantMessageRepository messageRepository,
                            AssistantToolRegistry toolRegistry,
                            ObjectMapper objectMapper,
                            AssistantConfigProperties configProperties,
                            DeterministicFallbackHandler fallbackHandler) {
        this.messageRepository = messageRepository;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.fallbackHandler = fallbackHandler;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();

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
     *   <li>Try Tier 2 deterministic pattern-matching first — if a known pattern
     *       matches, answer immediately without calling any AI provider</li>
     *   <li>If no Tier 2 pattern matched, fall through to the Groq → Gemini → OpenRouter
     *       provider chain for genuine natural-language understanding</li>
     *   <li>If every provider fails, return a graceful "unavailable" message
     *       that lists what the user CAN ask about directly</li>
     * </ol>
     */
    @Transactional
    public AssistantReply processMessage(User user, String userMessage) {
        // 1. Persist the user's message
        messageRepository.save(new AssistantMessage(user, AssistantMessageRole.USER, userMessage));

        // 2. Try Tier 2 deterministic pattern-matching FIRST
        //    (before any AI provider call — most real staff queries match here)
        AssistantReply tier2Reply = fallbackHandler.tryAnswer(userMessage, user.getRole());
        if (tier2Reply != null) {
            log.debug("Tier 2 matched query for user '{}': pattern={}",
                    user.getUsername(), userMessage);
            messageRepository.save(new AssistantMessage(user, AssistantMessageRole.ASSISTANT, tier2Reply.text()));
            return tier2Reply;
        }

        // 3. No Tier 2 match — load conversation history for AI providers
        List<AssistantMessage> history = messageRepository.findByUserOrderByCreatedAtAsc(user);

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

        // 6. Try each provider in order
        for (ModelProvider provider : providers) {
            if (!provider.hasApiKey()) {
                log.warn("Skipping provider {}: API key not set (env var {})",
                        provider.name(), provider.apiKeyEnvVar());
                continue;
            }

            AssistantReply reply = tryProvider(provider, messages, tools, allowedToolNames,
                    user);
            if (reply != null) {
                return reply;
            }
        }

        // 7. All providers failed — return unavailable message
        log.warn("All AI providers failed for user '{}'; returning unavailable message", user.getUsername());
        AssistantReply unavailable = fallbackHandler.unavailableMessage(user.getRole());
        messageRepository.save(new AssistantMessage(user, AssistantMessageRole.ASSISTANT, unavailable.text()));
        return unavailable;
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
            messageRepository.save(new AssistantMessage(user, AssistantMessageRole.ASSISTANT, reply.text()));
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
                                       User user) {
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
                messageRepository.save(new AssistantMessage(user, AssistantMessageRole.ASSISTANT, finalText));

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
        messageRepository.save(new AssistantMessage(user, AssistantMessageRole.ASSISTANT, fallback));

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
     * Returns the full message thread for a given user (read-only).
     */
    @Transactional(readOnly = true)
    public List<AssistantMessage> getHistory(User user) {
        return messageRepository.findByUserOrderByCreatedAtAsc(user);
    }

    /**
     * Returns distinct users who have assistant messages.
     */
    @Transactional(readOnly = true)
    public List<User> getUsersWithMessages() {
        return messageRepository.findDistinctUsersWithMessages();
    }

    // ---------------------------------------------------------------
    //  Private helpers
    // ---------------------------------------------------------------

    private String systemPromptForRole(Role role) {
        return switch (role) {
            case STAFF, KITCHEN ->
                "You are a helpful cafe assistant. You can answer questions about order status and menu items "
                + "using the tools available to you. Only answer using data returned by tool calls you actually made. "
                + "If a question needs information outside your available tools, say plainly that you don't have "
                + "access to that information and suggest asking a manager or admin. Never estimate, guess, or "
                + "answer from general knowledge. Never discuss what tools or capabilities other roles have.";
            case ADMIN ->
                "You are a helpful cafe assistant with access to sales reports, inventory, and kitchen queue data. "
                + "Only answer using data returned by tool calls you actually made. If a question needs information "
                + "outside your available tools, say plainly that you don't have access to that information. "
                + "Never estimate, guess, or answer from general knowledge. Never discuss what tools or capabilities "
                + "other roles have.";
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

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(provider.url()))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> httpResponse = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                int status = httpResponse.statusCode();

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
                            provider.name(), status, attempt + 1, httpResponse.body());
                    if (attempt == 0) {
                        Thread.sleep(RETRY_DELAY.toMillis());
                        continue;
                    }
                    return null;
                }

                if (status >= 400) {
                    log.warn("{} API error: status={}, body={}",
                            provider.name(), status, httpResponse.body());
                    return null;
                }

                return objectMapper.readValue(httpResponse.body(),
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

    public record AssistantReply(String text, List<SourceLink> links) {}

    public record SourceLink(String label, String url) {}
}