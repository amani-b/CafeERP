package com.cafeerp.assistant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Polished sidebar titles: replaces the "first 60 characters of the first
 * message" placeholder with a short AI-generated summary of the conversation
 * (3-6 words), e.g. "Checking order #482 status".
 *
 * <p>Two triggers:
 * <ol>
 *   <li><b>Live</b> — after each AI turn, if the conversation still carries
 *       its derived placeholder title, a title is generated asynchronously
 *       (after the transaction commits, on a daemon thread). The placeholder
 *       equality doubles as the "not yet summarized" marker, so NO schema
 *       change is needed.</li>
 *   <li><b>Backfill</b> — a scheduled job applies the same treatment to ALL
 *       previous conversations (archived included), a bounded batch per run
 *       so provider cost stays predictable. Runs shortly after startup and
 *       then hourly until every legacy thread has a summary title.</li>
 * </ol>
 *
 * <p>If every provider fails (no API key, rate limit, transport error), the
 * conversation simply keeps its derived title — the sidebar never shows an
 * error state, it just stays on the plain-text preview.
 */
@Component
public class AssistantTitleService {

    private static final Logger log = LoggerFactory.getLogger(AssistantTitleService.class);

    /** Generated titles are hard-capped to the same width as derived ones. */
    static final int MAX_TITLE_LENGTH = 60;

    /** Bounded backfill batch per run — predictable provider spend. */
    static final int BACKFILL_BATCH = 40;

    private static final String SYSTEM_PROMPT =
            "You name cafe chat threads. Given a customer's question and the assistant's answer, "
            + "write a title of 3 to 6 words that captures what the conversation is about. "
            + "Rules: plain text only — no quotes, no markdown, no emoji, no trailing period, "
            + "no more than 60 characters. Reply with ONLY the title text and nothing else.";

    private final List<ModelProvider> providers;
    private final ChatCompletionClient chatCompletionClient;
    private final AssistantConversationRepository conversationRepository;
    private final AssistantMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    /** Daemon per-task worker: title generation never blocks a chat turn. */
    private final ExecutorService titleExecutor = new ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "assistant-title");
                thread.setDaemon(true);
                return thread;
            });

    public AssistantTitleService(List<ModelProvider> providers,
                                 ChatCompletionClient chatCompletionClient,
                                 AssistantConversationRepository conversationRepository,
                                 AssistantMessageRepository messageRepository,
                                 ObjectMapper objectMapper) {
        this.providers = providers;
        this.chatCompletionClient = chatCompletionClient;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    /** Title generations in flight, by conversation id — lets the SSE
     *  stream hold the reply just long enough to deliver the fresh title. */
    private final ConcurrentHashMap<Long, CompletableFuture<Boolean>> pendingTitles =
            new ConcurrentHashMap<>();

    /**
     * Live hook: called after an AI turn persisted its reply. Generates a
     * summary title only when the conversation still carries its derived
     * placeholder — i.e. exactly once per conversation's lifetime.
     *
     * @return future completing (on the title thread) with true when a new
     *         title was generated and applied; the SSE stream awaits it so
     *         the sidebar can update within the same response.
     */
    public CompletableFuture<Boolean> maybeGenerateTitleAsync(AssistantConversation conversation,
                                                              String userMessage, String replyText) {
        if (conversation == null || conversation.getId() == null
                || userMessage == null || userMessage.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        String currentTitle = conversation.getTitle();
        if (currentTitle == null || !currentTitle.equals(AssistantConversation.deriveTitle(userMessage))) {
            return CompletableFuture.completedFuture(false); // already summarized
        }
        Long conversationId = conversation.getId();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingTitles.put(conversationId, future);
        Runnable job = () -> {
            boolean applied = generateTitle(conversationId, userMessage, replyText);
            future.complete(applied);
            pendingTitles.remove(conversationId);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // The reply must be visible to the title thread — run after commit.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    titleExecutor.execute(job);
                }
            });
        } else {
            titleExecutor.execute(job);
        }
        return future;
    }

    /**
     * The in-flight (or already-completed) title generation for a
     * conversation, or an already-completed "nothing" future when this turn
     * did not trigger one.
     */
    public CompletableFuture<Boolean> titleFutureFor(Long conversationId) {
        if (conversationId == null) {
            return CompletableFuture.completedFuture(false);
        }
        return pendingTitles.getOrDefault(conversationId,
                CompletableFuture.completedFuture(false));
    }

    /** The conversation's current title, if it exists. */
    public Optional<String> latestTitleOf(Long conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        return conversationRepository.findById(conversationId)
                .map(AssistantConversation::getTitle)
                .filter(title -> title != null && !title.isBlank());
    }

    /**
     * Backfill for all previous chatrooms: finds conversations still carrying
     * their derived placeholder title and summarizes them. Runs 5s after
     * startup (so legacy threads get real names almost immediately), then
     * every 5 minutes; each run handles at most {@link #BACKFILL_BATCH}
     * conversations so provider cost is bounded, and successive runs mop up
     * the rest.
     *
     * @return number of conversations given a summary title this run
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 5_000)
    public int backfillTitles() {
        int updated = 0;
        try {
            for (AssistantConversation conversation : conversationRepository.findAll()) {
                if (updated >= BACKFILL_BATCH) {
                    break;
                }
                if (summarizeIfNeeded(conversation)) {
                    updated++;
                }
            }
        } catch (Exception e) {
            log.warn("Title backfill run failed: {}", e.getMessage());
        }
        if (updated > 0) {
            log.info("Backfilled {} assistant conversation title(s) with AI summaries", updated);
        }
        return updated;
    }

    /** One backfill candidate: summarize if still a placeholder (and replyable). */
    private boolean summarizeIfNeeded(AssistantConversation conversation) {
        String title = conversation.getTitle();
        if (title == null) {
            return false; // never used
        }
        List<AssistantMessage> messages =
                messageRepository.findByConversationOrderByCreatedAtAscIdAsc(conversation);
        String firstUserMessage = null;
        String firstReply = null;
        for (AssistantMessage message : messages) {
            if (firstUserMessage == null && message.getRole() == AssistantMessageRole.USER) {
                firstUserMessage = message.getContent();
            }
            if (message.getRole() == AssistantMessageRole.ASSISTANT && message.getContent() != null
                    && !message.getContent().isBlank()) {
                firstReply = message.getContent();
                break; // first reply after the first user message is enough context
            }
        }
        if (firstUserMessage == null) {
            return false;
        }
        if (!title.equals(AssistantConversation.deriveTitle(firstUserMessage))) {
            return false; // already summarized
        }
        return generateTitle(conversation.getId(), firstUserMessage, firstReply);
    }

    /**
     * Asks the providers for a short summary title and applies it. Returns
     * true if the title was replaced; on any failure the conversation keeps
     * its existing title.
     */
    private boolean generateTitle(Long conversationId, String userMessage, String replyText) {
        try {
            String snippet = summarize(userMessage, replyText);
            if (snippet == null) {
                return false; // every provider failed — keep the placeholder
            }
            return applyTitle(conversationId, snippet);
        } catch (Exception e) {
            log.debug("Title generation failed for conversation {}: {}",
                    conversationId, e.getMessage());
            return false;
        }
    }

    /** Applies the generated title inside its own short transaction. */
    @org.springframework.transaction.annotation.Transactional
    protected boolean applyTitle(Long conversationId, String newTitle) {
        return conversationRepository.findById(conversationId)
                .filter(conversation -> conversation.getTitle() != null)
                .map(conversation -> {
                    conversation.setTitle(newTitle);
                    conversationRepository.save(conversation);
                    return true;
                })
                .orElse(false);
    }

    /** Tries each provider in order; returns a sanitized title or null. */
    private String summarize(String userMessage, String replyText) {
        for (ModelProvider provider : providers) {
            if (!provider.hasApiKey()) {
                continue;
            }
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("model", provider.model());
                body.put("messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", buildUserPrompt(userMessage, replyText))));
                body.put("max_tokens", 32);
                body.put("temperature", 0.3);

                ChatCompletionClient.Result result = chatCompletionClient.post(
                        provider.url(), provider.apiKey(), objectMapper.writeValueAsString(body));
                if (result.status() != 200) {
                    log.debug("Title provider {} returned {}", provider.name(), result.status());
                    continue;
                }
                Map<String, Object> response = objectMapper.readValue(result.body(),
                        new TypeReference<Map<String, Object>>() { });
                List<Map<String, Object>> choices =
                        (List<Map<String, Object>>) response.get("choices");
                if (choices == null || choices.isEmpty()) {
                    continue;
                }
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                String title = sanitizeTitle((String) message.get("content"));
                if (title != null) {
                    return title;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.debug("Title provider {} failed: {}", provider.name(), e.getMessage());
            }
        }
        return null;
    }

    private String buildUserPrompt(String userMessage, String replyText) {
        StringBuilder sb = new StringBuilder("Customer asked: ")
                .append(truncate(userMessage, 400));
        if (replyText != null && !replyText.isBlank()) {
            sb.append("\n\nAssistant answered: ").append(truncate(replyText, 400));
        }
        sb.append("\n\nTitle:");
        return sb.toString();
    }

    /**
     * Cleans the model output into a safe sidebar title: strips wrapping
     * quotes/markup, collapses whitespace, caps at {@link #MAX_TITLE_LENGTH}
     * on a word boundary. Returns null when nothing usable remains.
     */
    static String sanitizeTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String title = raw.replaceAll("\\*+|_+|`+", "")   // markdown emphasis/code
                .replace('"', ' ')
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // Drop symmetric wrapping quotes if the model insisted.
        if (title.length() >= 2) {
            char first = title.charAt(0);
            char last = title.charAt(title.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '\u2018' && last == '\u2019')) {
                title = title.substring(1, title.length() - 1).trim();
            }
        }
        if (title.endsWith(".")) {
            title = title.substring(0, title.length() - 1).trim();
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            int cut = title.lastIndexOf(' ', MAX_TITLE_LENGTH);
            title = cut > MAX_TITLE_LENGTH / 2 ? title.substring(0, cut) : title.substring(0, MAX_TITLE_LENGTH);
            title = title.trim();
        }
        return title.length() >= 3 ? title : null;
    }

    private static String truncate(String text, int max) {
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max) + "\u2026";
    }
}
