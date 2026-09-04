package com.cafeerp.assistant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the AI sidebar-title service: sanitization, live hook
 * gating, provider fallback behaviour, and the backfill path.
 */
class AssistantTitleServiceTest {

    private ModelProvider provider;
    private ChatCompletionClient client;
    private AssistantConversationRepository conversationRepository;
    private AssistantMessageRepository messageRepository;
    private AssistantTitleService titleService;

    @BeforeEach
    void setUp() {
        provider = mock(ModelProvider.class);
        when(provider.hasApiKey()).thenReturn(true);
        when(provider.name()).thenReturn("test");
        when(provider.url()).thenReturn("https://example.test/v1");
        when(provider.apiKey()).thenReturn("key");
        when(provider.model()).thenReturn("test-model");
        when(provider.effectiveTitleModel()).thenReturn("test-title-model");

        client = mock(ChatCompletionClient.class);
        conversationRepository = mock(AssistantConversationRepository.class);
        messageRepository = mock(AssistantMessageRepository.class);

        titleService = new AssistantTitleService(List.of(provider), client,
                conversationRepository, messageRepository, new ObjectMapper());
    }

    private static ChatCompletionClient.Result ok(String title) {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + com.fasterxml.jackson.databind.node.TextNode.valueOf(title).toString() + "}}]}";
        return new ChatCompletionClient.Result(200, body);
    }

    @Test
    void sanitizeTitle_stripsQuotesAndMarkdown() {
        assertEquals("Checking order 482 status",
                AssistantTitleService.sanitizeTitle("\"**Checking order 482 status**\""));
        assertEquals("Weekly sales recap",
                AssistantTitleService.sanitizeTitle("`Weekly sales recap`.\n"));
    }

    @Test
    void sanitizeTitle_capsLengthAndRejectsJunk() {
        assertEquals(60, AssistantTitleService.sanitizeTitle("x".repeat(200)).length());
        assertNull(AssistantTitleService.sanitizeTitle("ok"));
        assertNull(AssistantTitleService.sanitizeTitle(null));
        assertNull(AssistantTitleService.sanitizeTitle("   "));
    }

    @Test
    void sanitizeTitle_keepsMildOvershoot_rejectsRunawayWordCounts() {
        // 8 words: mildly over the 3-6 brief — kept (and length-capped).
        assertEquals("one two three four five six seven eight",
                AssistantTitleService.sanitizeTitle("one two three four five six seven eight"));
        // 13 words: wildly outside the brief — rejected rather than trusted.
        assertNull(AssistantTitleService.sanitizeTitle(
                "one two three four five six seven eight nine ten eleven twelve thirteen"));
    }

    @Test
    void summarize_usesSmallTitleModel_withRoomyTokenBudget() throws Exception {
        String question = "What is the status of order 482?";
        AssistantConversation conversation = mock(AssistantConversation.class);
        when(conversation.getId()).thenReturn(1L);
        when(conversation.getTitle()).thenReturn(AssistantConversation.deriveTitle(question));
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conversation));
        when(client.post(anyString(), anyString(), anyString()))
                .thenReturn(ok("Checking order 482 status"));

        titleService.maybeGenerateTitleAsync(conversation, question, "It is READY.");
        verify(conversation, timeout(5000)).setTitle("Checking order 482 status");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(5000)).post(anyString(), anyString(), body.capture());
        // The small/fast title model — NOT the tool-calling chat model.
        assertTrue(body.getValue().contains("\"model\":\"test-title-model\""),
                "title calls must use the dedicated small title model: " + body.getValue());
        assertTrue(body.getValue().contains("\"model\":\"test-model\"") == false,
                "title calls must never use the main chat model: " + body.getValue());
        // Roomy completion budget: a tiny cap (e.g. 32) starves reasoning
        // models and returns empty content — the original silent killer.
        assertTrue(body.getValue().contains("\"max_tokens\":512"),
                "title calls need a reasoning-safe completion budget: " + body.getValue());
        assertTrue(body.getValue().contains("\"max_tokens\":32,") == false,
                "the starved 32-token budget must stay gone: " + body.getValue());
    }

    @Test
    void liveHook_placeholderTitle_generatesAndSavesSummary() throws Exception {
        String question = "What is the status of order 482?";
        AssistantConversation conversation = mock(AssistantConversation.class);
        when(conversation.getId()).thenReturn(1L);
        when(conversation.getTitle()).thenReturn(AssistantConversation.deriveTitle(question));
        when(conversationRepository.findById(1L)).thenReturn(Optional.of(conversation));
        when(client.post(anyString(), anyString(), contains("order 482")))
                .thenReturn(ok("Checking order 482 status"));

        titleService.maybeGenerateTitleAsync(conversation, question, "It is READY.");

        ArgumentCaptor<AssistantConversation> captor =
                ArgumentCaptor.forClass(AssistantConversation.class);
        verify(conversationRepository, timeout(5000)).save(captor.capture());
        // conversation is a Mockito mock — the title write is verified directly
        verify(conversation, timeout(5000)).setTitle("Checking order 482 status");
        assertEquals(conversation, captor.getValue());
    }

    @Test
    void liveHook_alreadySummarized_doesNothing() {
        AssistantConversation conversation = mock(AssistantConversation.class);
        when(conversation.getId()).thenReturn(1L);
        when(conversation.getTitle()).thenReturn("Already summarized thread");
        titleService.maybeGenerateTitleAsync(conversation, "Some question", "Some answer");
        verify(conversationRepository, timeout(500).times(0))
                .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void backfill_untitledConversationWithMessages_getsRealTitle() throws Exception {
        String question = "Which syrups are we out of?";
        // Untitled (null title) but with a real exchange — backfill must
        // treat it as needing a title, not skip it as "never used".
        AssistantConversation conversation =
                new AssistantConversation(new User("staff9", "p", Role.STAFF));
        conversation.setId(77L);

        AssistantMessage questionMessage = new AssistantMessage(
                conversation.getUser(), AssistantMessageRole.USER, question, conversation);
        AssistantMessage replyMessage = new AssistantMessage(
                conversation.getUser(), AssistantMessageRole.ASSISTANT,
                "Hazelnut and vanilla.", conversation);
        when(conversationRepository.findAll()).thenReturn(List.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtAscIdAsc(conversation))
                .thenReturn(List.of(questionMessage, replyMessage));
        when(conversationRepository.findById(77L)).thenReturn(Optional.of(conversation));
        when(client.post(anyString(), anyString(), anyString()))
                .thenReturn(ok("Syrup stock shortage"));

        int updated = titleService.backfillTitles();

        assertEquals(1, updated);
        assertEquals("Syrup stock shortage", conversation.getTitle());
    }

    @Test
    void backfill_summarizesPlaceholderConversations() throws Exception {
        String question = "How many croissants sold today?";
        AssistantConversation conversation =
                new AssistantConversation(new User("staff1", "p", Role.STAFF));
        conversation.setTitle(AssistantConversation.deriveTitle(question));

        AssistantMessage questionMessage = new AssistantMessage(
                conversation.getUser(), AssistantMessageRole.USER, question, conversation);
        AssistantMessage replyMessage = new AssistantMessage(
                conversation.getUser(), AssistantMessageRole.ASSISTANT, "42 croissants.", conversation);
        when(conversationRepository.findAll()).thenReturn(List.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtAscIdAsc(conversation))
                .thenReturn(List.of(questionMessage, replyMessage));
        when(conversationRepository.findById(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(client.post(anyString(), anyString(), anyString()))
                .thenReturn(ok("Croissant sales for today"));

        int updated = titleService.backfillTitles();

        assertEquals(1, updated);
        assertEquals("Croissant sales for today", conversation.getTitle());
    }

    @Test
    void backfill_providerFailure_keepsPlaceholderTitle() throws Exception {
        String question = "Which items are low in stock?";
        AssistantConversation conversation =
                new AssistantConversation(new User("staff1", "p", Role.STAFF));
        conversation.setTitle(AssistantConversation.deriveTitle(question));

        AssistantMessage questionMessage = new AssistantMessage(
                conversation.getUser(), AssistantMessageRole.USER, question, conversation);
        AssistantMessage replyMessage = new AssistantMessage(
                conversation.getUser(), AssistantMessageRole.ASSISTANT, "Three items.", conversation);
        when(conversationRepository.findAll()).thenReturn(List.of(conversation));
        when(messageRepository.findByConversationOrderByCreatedAtAscIdAsc(conversation))
                .thenReturn(List.of(questionMessage, replyMessage));
        when(client.post(anyString(), anyString(), anyString()))
                .thenThrow(new java.net.ConnectException("down"));

        int updated = titleService.backfillTitles();

        assertEquals(0, updated);
        assertTrue(conversation.getTitle().startsWith(question.substring(0, 10)));
        verify(conversationRepository, org.mockito.Mockito.times(0))
                .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void backfill_skipsConversationsWithoutMessagesOrAlreadySummarized() throws Exception {
        AssistantConversation empty =
                new AssistantConversation(new User("a", "p", Role.STAFF));
        empty.setTitle(null); // never used
        AssistantConversation summarized =
                new AssistantConversation(new User("b", "p", Role.STAFF));
        summarized.setTitle("Already a summary");
        when(conversationRepository.findAll()).thenReturn(List.of(empty, summarized));

        assertEquals(0, titleService.backfillTitles());
        verify(client, org.mockito.Mockito.times(0)).post(anyString(), anyString(), anyString());
    }
}
