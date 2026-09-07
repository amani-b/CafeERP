package com.cafeerp.assistant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 *       previous conversations whose title is still the raw derived first
 *       message (or missing entirely), draining the whole backlog each run
 *       with bounded concurrency. Runs shortly after startup and then every
 *       5 minutes until every legacy thread has a summary title.</li>
 * </ol>
 *
 * <p>Titles always run on each provider's small/fast {@code titleModel} as a
 * PLAIN completion — no tools, no function calling — so titling is fast and
 * can never touch the ERP-knowledge or agentic tool-calling pipeline.
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

    /** Target title brevity (words). Output much wider than 2x is rejected. */
    static final int MAX_TITLE_WORDS = 6;

    /**
     * Completion budget for a title call. Generous on purpose: reasoning
     * models spend hidden "thinking" tokens from this same budget, and a
     * tiny cap (e.g. 32) makes them return EMPTY content — the whole reply
     * is consumed by reasoning before a single word of the title appears.
     * Still tightly bounded, so free-tier spend stays predictable.
     */
    static final int TITLE_MAX_TOKENS = 512;

    /** One quick retry when a provider rate-limits (429) before failover. */
    static final long RATE_LIMIT_RETRY_DELAY_MS = 2_000;

    /** Bounded backfill concurrency against the free-tier providers. */
    static final int BACKFILL_CONCURRENCY = 5;

    /**
     * Master switch for the SCHEDULED backfill trigger only. On-demand
     * (direct) calls to {@link #backfillTitles()} are unaffected, so the
     * pass can still be run manually or from a test. Default: enabled.
     */
    private final boolean scheduledBackfillEnabled;

    /** Whole-run budget for one backfill pass so a stuck call can't stall it. */
    static final long BACKFILL_BUDGET_MS = 10 * 60_000L;

    /** How long a completed title future lingers for the SSE stream to pick up. */
    static final long PENDING_TITLE_TTL_SECONDS = 30;

    private static final String SYSTEM_PROMPT =
            "You generate sidebar titles for cafe chat threads. Given the customer's message and the "
            + "assistant's answer, reply with a 3 to 6 word summary of what the conversation is about. "
            + "Rules: NEVER repeat or echo the customer's own words back as the title; NEVER use generic "
            + "labels like \"New chat\" or \"Conversation\"; plain text only — no quotes, no markdown, "
            + "no emoji, no trailing period; at most 6 words. Reply with ONLY the title text and nothing else.";

    /**
     * Single-round-trip instant titling: on the FIRST turn of a conversation
     * the chat system prompt carries {@link #TITLE_TAG_INSTRUCTION}, asking
     * the model to end its (already-paid-for) reply with a delimited title
     * tag. The tag is parsed and stripped server-side before anything is
     * persisted or shown, and the title lands in the same DB write window as
     * the reply itself — no second network round-trip, so the sidebar title
     * appears at effectively the same moment the reply is confirmed
     * delivered. Plain completion text only, never routed through the
     * ERP-knowledge/agentic tool-calling pipeline.
     */
    public static final String TITLE_TAG_INSTRUCTION =
            "Conversation-title instruction (this is the first exchange of a new chat thread): "
            + "end your FINAL answer with the thread's sidebar title on its own last line, exactly in "
            + "this form: <title>3 to 6 word summary of the conversation topic</title> "
            + "Rules for the title: 3 to 6 words, a genuine summary of the topic (never just echo the "
            + "user's words, never a generic label like \"New chat\"), plain text only — no quotes, "
            + "no markdown, no emoji, no trailing period. The tag line is stripped before the user sees "
            + "your answer, so never refer to it in your text. Put the tag ONLY in your final answer "
            + "text — never inside tool calls.";

    /** Delimited title tag produced by the model in its first-turn reply. */
    static final java.util.regex.Pattern TITLE_TAG_PATTERN = java.util.regex.Pattern.compile(
            "<title\\s*>(.+?)</title\\s*>",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    /**
     * A first-turn reply with its embedded title tag removed: the text the
     * user actually sees, plus the sanitized sidebar title (null when the
     * model emitted no usable title even though a tag was present).
     */
    public record InlineTitle(String visibleText, String title) {}

    private final List<ModelProvider> providers;
    private final ChatCompletionClient chatCompletionClient;
    private final AssistantConversationRepository conversationRepository;
    private final AssistantMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    /** Title generations in flight, by conversation id — lets the SSE
     *  stream hold the reply just long enough to deliver the fresh title. */
    private final ConcurrentHashMap<Long, CompletableFuture<Boolean>> pendingTitles =
            new ConcurrentHashMap<>();

    /** Daemon per-task worker: title generation never blocks a chat turn. */
    private final ExecutorService titleExecutor = new ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "assistant-title");
                thread.setDaemon(true);
                return thread;
            });

    /** Delays removal of finished title futures so the SSE stream's
     *  titleFutureFor() lookup can never lose the race with cleanup. */
    private final ScheduledExecutorService titleJanitor = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "assistant-title-janitor");
                thread.setDaemon(true);
                return thread;
            });

    /** Bounded-concurrency worker pool for the backfill drain. */
    private final ExecutorService backfillExecutor = Executors.newFixedThreadPool(
            BACKFILL_CONCURRENCY,
            runnable -> {
                Thread thread = new Thread(runnable, "assistant-title-backfill");
                thread.setDaemon(true);
                return thread;
            });

    /** Production wiring: providers come from configuration — the SAME
     *  source the chat path builds its chain from. (ModelProvider objects
     *  are NOT Spring beans; injecting List&lt;ModelProvider&gt; resolved to
     *  null and made every title call fail invisibly.) */
    @Autowired
    public AssistantTitleService(AssistantConfigProperties configProperties,
                                 ChatCompletionClient chatCompletionClient,
                                 AssistantConversationRepository conversationRepository,
                                 AssistantMessageRepository messageRepository,
                                 ObjectMapper objectMapper,
                                 @Value("${assistant.title.backfill-enabled:true}")
                                 boolean scheduledBackfillEnabled) {
        this(fromConfig(configProperties), chatCompletionClient,
                conversationRepository, messageRepository, objectMapper, scheduledBackfillEnabled);
    }

    /** Secondary constructor (unit tests inject a provider list directly). */
    public AssistantTitleService(List<ModelProvider> providers,
                                 ChatCompletionClient chatCompletionClient,
                                 AssistantConversationRepository conversationRepository,
                                 AssistantMessageRepository messageRepository,
                                 ObjectMapper objectMapper) {
        this(providers, chatCompletionClient, conversationRepository,
                messageRepository, objectMapper, true);
    }

    public AssistantTitleService(List<ModelProvider> providers,
                                 ChatCompletionClient chatCompletionClient,
                                 AssistantConversationRepository conversationRepository,
                                 AssistantMessageRepository messageRepository,
                                 ObjectMapper objectMapper,
                                 boolean scheduledBackfillEnabled) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.chatCompletionClient = chatCompletionClient;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.scheduledBackfillEnabled = scheduledBackfillEnabled;
    }

    private static List<ModelProvider> fromConfig(AssistantConfigProperties configProperties) {
        return configProperties.getProviders().stream()
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
            // Delayed removal: the SSE stream reads titleFutureFor() right
            // after the reply event; an immediate remove() could race and
            // hide a freshly generated title from the sidebar update.
            titleJanitor.schedule(
                    () -> pendingTitles.remove(conversationId, future),
                    PENDING_TITLE_TTL_SECONDS, TimeUnit.SECONDS);
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
     * Scheduled trigger for the startup/periodic backfill pass. Gated by
     * {@code assistant.title.backfill-enabled} (default on) so embedders and
     * hermetic tests can run the pass purely on demand — a background job
     * that mutates shared state on a 5-minute timer is hostile to tests and
     * surprised nobody by firing mid-assertion.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 5_000)
    public void scheduledBackfillTitles() {
        if (!scheduledBackfillEnabled) {
            return;
        }
        backfillTitles();
    }

    /**
     * Backfill for all previous chatrooms: finds conversations still carrying
     * their derived placeholder title (or no title at all despite having
     * messages) and summarizes them. Runs 5s after startup (so legacy threads
     * get real names almost immediately), then every 5 minutes. Each run is a
     * CONTINUOUS pass: it drains the entire current backlog with bounded
     * concurrency ({@link #BACKFILL_CONCURRENCY} parallel provider calls) and
     * a whole-run time budget, so one stuck request can never stall it.
     *
     * @return number of conversations given a summary title this run
     */
    public int backfillTitles() {
        List<BackfillCandidate> candidates = new ArrayList<>();
        try {
            for (AssistantConversation conversation : conversationRepository.findAll()) {
                if (conversation == null) {
                    continue;
                }
                candidateFor(conversation).ifPresent(candidates::add);
            }
        } catch (Exception e) {
            log.warn("Title backfill could not list conversations: {}", e.getMessage());
            return 0;
        }
        int needed = candidates.size();
        if (needed == 0) {
            return 0;
        }
        log.info("Title backfill: {} conversation(s) on raw/missing titles; summarizing with concurrency {}",
                needed, BACKFILL_CONCURRENCY);

        long deadline = System.currentTimeMillis() + BACKFILL_BUDGET_MS;
        AtomicInteger updated = new AtomicInteger();
        List<Future<?>> inFlight = new ArrayList<>(needed);
        for (BackfillCandidate candidate : candidates) {
            inFlight.add(backfillExecutor.submit(() -> {
                if (System.currentTimeMillis() >= deadline) {
                    return; // run budget exhausted — the next run mops up
                }
                if (generateTitle(candidate.conversation().getId(),
                        candidate.firstUserMessage(), candidate.firstReply())) {
                    updated.incrementAndGet();
                }
            }));
        }
        for (Future<?> task : inFlight) {
            try {
                task.get(Math.max(1_000, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Individual failures are already logged; keep draining.
            }
        }
        int titled = updated.get();
        log.info("Title backfill: before={} conversation(s) needing titles, after={} titled, {} pending for next run",
                needed, titled, needed - titled);
        return titled;
    }

    /** One backfill work item: a conversation plus the context to summarize. */
    private record BackfillCandidate(AssistantConversation conversation,
                                     String firstUserMessage,
                                     String firstReply) {}

    /**
     * TRUE needing-title check: the conversation still carries its raw
     * derived placeholder (or no title at all) AND has an exchange to
     * summarize. Filtering here — not inside the worker — keeps the run's
     * before/after counts honest.
     */
    private Optional<BackfillCandidate> candidateFor(AssistantConversation conversation) {
        String title = conversation.getTitle();
        boolean untitled = title == null || title.isBlank();
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
            return Optional.empty(); // never used — nothing to summarize
        }
        if (!untitled && !title.equals(AssistantConversation.deriveTitle(firstUserMessage))) {
            return Optional.empty(); // already summarized
        }
        return Optional.of(new BackfillCandidate(conversation, firstUserMessage, firstReply));
    }

    /**
     * Parses a first-turn reply for an embedded {@code <title>…</title>} tag.
     * Every tag occurrence is stripped from the returned visible text (the
     * tag must never reach the user or the persisted message); the sidebar
     * title is the LAST tag's content, run through the same
     * {@link #sanitizeTitle(String)} guard as second-call titles (null when
     * nothing usable remains, in which case callers fall back to the async
     * second-call path). Returns null when the reply carries no tag at all.
     */
    public static InlineTitle extractInlineTitle(String rawReply) {
        if (rawReply == null || !TITLE_TAG_PATTERN.matcher(rawReply).find()) {
            return null;
        }
        java.util.regex.Matcher matcher = TITLE_TAG_PATTERN.matcher(rawReply);
        String lastCandidate = null;
        while (matcher.find()) {
            lastCandidate = matcher.group(1);
        }
        String visibleText = TITLE_TAG_PATTERN.matcher(rawReply).replaceAll("").trim();
        return new InlineTitle(visibleText, sanitizeTitle(lastCandidate));
    }

    /**
     * Applies an already-generated (single-round-trip) title synchronously,
     * in the same window as the reply it arrived with. Also records an
     * already-completed pending-title future so the SSE stream's
     * {@code titleFutureFor()} lookup resolves instantly and the
     * {@code title} event ships in the same stream as the {@code reply}
     * event. Returns true when the title was applied.
     */
    public boolean applyTitleNow(Long conversationId, String newTitle) {
        if (conversationId == null || newTitle == null || newTitle.isBlank()) {
            return false;
        }
        boolean applied = applyTitle(conversationId, newTitle);
        if (applied) {
            log.info("Instant title for conversation {}: \"{}\"", conversationId, newTitle);
            CompletableFuture<Boolean> done = CompletableFuture.completedFuture(true);
            pendingTitles.put(conversationId, done);
            titleJanitor.schedule(
                    () -> pendingTitles.remove(conversationId, done),
                    PENDING_TITLE_TTL_SECONDS, TimeUnit.SECONDS);
        }
        return applied;
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
            boolean applied = applyTitle(conversationId, snippet);
            if (applied) {
                log.info("Title for conversation {}: \"{}\"", conversationId, snippet);
            }
            return applied;
        } catch (Exception e) {
            log.warn("Title generation failed for conversation {}: {}",
                    conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * Applies the generated title in its own short transaction (the
     * repository save is transactional itself; a local {@code @Transactional}
     * would be silently bypassed by self-invocation anyway).
     */
    private boolean applyTitle(Long conversationId, String newTitle) {
        return conversationRepository.findById(conversationId)
                .map(conversation -> {
                    conversation.setTitle(newTitle);
                    conversationRepository.save(conversation);
                    return true;
                })
                .orElse(false);
    }

    /** Tries each provider in order; returns a sanitized title or null. */
    @SuppressWarnings("unchecked")
    private String summarize(String userMessage, String replyText) {
        List<String> failures = new ArrayList<>();
        for (ModelProvider provider : providers) {
            if (!provider.hasApiKey()) {
                log.debug("Title provider {} skipped: no API key (env var {})",
                        provider.name(), provider.apiKeyEnvVar());
                continue;
            }
            // Small/fast plain-completion model — never the tool-calling chat model.
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    Map<String, Object> body = new HashMap<>();
                    body.put("model", provider.effectiveTitleModel());
                    // Amharic threads get native idiomatic titles in the user's
                    // own script — never a stiff translation of an English one.
                    String systemPrompt = SYSTEM_PROMPT
                            + AmharicLanguageSupport.titleHintFor(userMessage);
                    body.put("messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", buildUserPrompt(userMessage, replyText))));
                    body.put("max_tokens", TITLE_MAX_TOKENS);
                    body.put("temperature", 0.3);

                    ChatCompletionClient.Result result = chatCompletionClient.post(
                            provider.url(), provider.apiKey(), objectMapper.writeValueAsString(body));
                    if (result.status() == 429 && attempt == 0) {
                        log.debug("Title provider {} rate-limited; retrying once before failover",
                                provider.name());
                        Thread.sleep(RATE_LIMIT_RETRY_DELAY_MS);
                        continue;
                    }
                    if (result.status() != 200) {
                        log.debug("Title provider {} returned {}: {}",
                                provider.name(), result.status(), abbreviate(result.body()));
                        failures.add(provider.name() + " HTTP " + result.status());
                        break;
                    }
                    Map<String, Object> response = objectMapper.readValue(result.body(),
                            new TypeReference<Map<String, Object>>() { });
                    List<Map<String, Object>> choices =
                            (List<Map<String, Object>>) response.get("choices");
                    if (choices == null || choices.isEmpty()) {
                        failures.add(provider.name() + " empty choices");
                        break;
                    }
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String title = sanitizeTitle((String) message.get("content"));
                    if (title != null) {
                        log.debug("Title provider {} produced \"{}\"", provider.name(), title);
                        return title;
                    }
                    // Empty content is the classic symptom of a starved
                    // reasoning budget — keep failing over, but say why.
                    log.debug("Title provider {} produced no usable title", provider.name());
                    failures.add(provider.name() + " unusable output");
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception e) {
                    log.debug("Title provider {} failed: {}", provider.name(), e.getMessage());
                    failures.add(provider.name() + " " + e.getClass().getSimpleName());
                    break;
                }
            }
        }
        log.warn("Title generation failed on every provider: {}",
                failures.isEmpty()
                        ? "no provider has an API key configured (graceful fallback: raw message title)"
                        : String.join("; ", failures));
        return null;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200) + "\u2026";
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
     * on a word boundary, and rejects output wildly outside the 3-6 word
     * brief (the model is never trusted blindly). Returns null when nothing
     * usable remains.
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
        // Defensive length/word guard: mildly-overshooting titles are kept
        // (and truncated), wildly-off-brief ones are rejected outright.
        if (title.split("\\s+").length > MAX_TITLE_WORDS * 2) {
            return null;
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
