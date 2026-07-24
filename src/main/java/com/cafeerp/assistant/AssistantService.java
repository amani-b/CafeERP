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
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "llama-3.3-70b-versatile";

    private final AssistantMessageRepository messageRepository;
    private final AssistantToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AssistantService(AssistantMessageRepository messageRepository,
                            AssistantToolRegistry toolRegistry,
                            ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * Process a user message and return the assistant's reply with source links.
     */
    @Transactional
    public AssistantReply processMessage(User user, String userMessage) {
        // 1. Persist the user's message
        messageRepository.save(new AssistantMessage(user, AssistantMessageRole.USER, userMessage));

        // 2. Load conversation history
        List<AssistantMessage> history = messageRepository.findByUserOrderByCreatedAtAsc(user);

        // 3. Build the messages array for the API
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

        // 4. Determine role-appropriate tools
        List<Map<String, Object>> tools = toolsForRole(user.getRole());

        // 5. Tool-call loop
        List<String> firedToolNames = new ArrayList<>();
        Map<String, String> toolNameToUrl = buildSourceUrlMap(user.getRole());

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            Map<String, Object> response = callGroq(messages, tools);
            if (response == null) {
                return new AssistantReply("I couldn't get an answer right now. Please try again.", List.of());
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
            messages.add(message);

            // Execute each tool call
            for (Map<String, Object> tc : toolCalls) {
                String id = (String) tc.get("id");
                Map<String, Object> function = (Map<String, Object>) tc.get("function");
                String name = (String) function.get("name");
                String args = (String) function.get("arguments");

                firedToolNames.add(name);
                log.debug("Executing tool: {} with args: {}", name, args);

                String result = toolRegistry.execute(name, args);

                Map<String, Object> toolMessage = new HashMap<>();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", id);
                toolMessage.put("content", result);
                messages.add(toolMessage);
            }
        }

        // Cap reached — graceful fallback
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> callGroq(List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools) {
        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.error("GROQ_API_KEY environment variable is not set");
            return null;
        }

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", MODEL);
            body.put("messages", messages);
            body.put("tools", tools);
            body.put("tool_choice", "auto");

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() >= 400) {
                log.warn("Groq API error: status={}, body={}",
                        httpResponse.statusCode(), httpResponse.body());
                return null;
            }

            return objectMapper.readValue(httpResponse.body(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Groq API call failed", e);
            return null;
        }
    }

    // ---------------------------------------------------------------
    //  Value objects
    // ---------------------------------------------------------------

    public record AssistantReply(String text, List<SourceLink> links) {}

    public record SourceLink(String label, String url) {}
}