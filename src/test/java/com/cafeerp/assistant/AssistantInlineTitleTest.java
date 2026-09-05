package com.cafeerp.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for single-round-trip instant titling: parsing/stripping the
 * model's {@code <title>} tag and applying the title synchronously.
 */
class AssistantInlineTitleTest {

    private AssistantTitleService titleService;
    private AssistantConversationRepository conversationRepository;

    @BeforeEach
    void setUp() {
        conversationRepository = Mockito.mock(AssistantConversationRepository.class);
        titleService = new AssistantTitleService(List.of(),
                Mockito.mock(ChatCompletionClient.class),
                conversationRepository,
                Mockito.mock(AssistantMessageRepository.class),
                new ObjectMapper());
    }

    @Test
    void extractInlineTitle_stripsTagAndReturnsSanitizedTitle() {
        String reply = "Batch your shots and keep steaming.\n<title>Espresso rush survival tips</title>";
        AssistantTitleService.InlineTitle inline =
                AssistantTitleService.extractInlineTitle(reply);
        assertNotNull(inline);
        assertEquals("Batch your shots and keep steaming.", inline.visibleText());
        assertEquals("Espresso rush survival tips", inline.title());
    }

    @Test
    void extractInlineTitle_noTag_returnsNull() {
        assertNull(AssistantTitleService.extractInlineTitle("Just a plain reply."));
        assertNull(AssistantTitleService.extractInlineTitle(null));
    }

    @Test
    void extractInlineTitle_junkTitle_stripsTagButYieldsNoTitle() {
        // Tag must never leak to the user even when the title is unusable;
        // callers then fall back to the async second-call path.
        String reply = "Answer here.\n<title>ok</title>";
        AssistantTitleService.InlineTitle inline =
                AssistantTitleService.extractInlineTitle(reply);
        assertNotNull(inline);
        assertEquals("Answer here.", inline.visibleText());
        assertNull(inline.title());
    }

    @Test
    void extractInlineTitle_usesLastTag_sanitizesMarkup() {
        String reply = "Text.\n<title>\"**First candidate**\"</title> more text "
                + "<title>Syrup stock shortage.</title>";
        AssistantTitleService.InlineTitle inline =
                AssistantTitleService.extractInlineTitle(reply);
        assertNotNull(inline);
        assertEquals("Syrup stock shortage", inline.title());
        assertTrue(!inline.visibleText().contains("<title>"),
                "no tag may survive in the visible text: " + inline.visibleText());
    }

    @Test
    void applyTitleNow_savesSynchronouslyAndCompletesPendingFuture() throws Exception {
        AssistantConversation conversation =
                new AssistantConversation(new User("staff1", "p", Role.STAFF));
        conversation.setId(42L);
        conversation.setTitle(AssistantConversation.deriveTitle("Which syrups are out?"));
        when(conversationRepository.findById(42L)).thenReturn(Optional.of(conversation));

        assertTrue(titleService.applyTitleNow(42L, "Syrup stock shortage"));
        assertEquals("Syrup stock shortage", conversation.getTitle());
        Mockito.verify(conversationRepository).save(conversation);

        // The SSE stream's titleFutureFor() lookup must resolve instantly so
        // the title event ships in the same stream as the reply event.
        assertEquals(Boolean.TRUE,
                titleService.titleFutureFor(42L).get(1, TimeUnit.SECONDS));
        assertEquals(Optional.of("Syrup stock shortage"), titleService.latestTitleOf(42L));
    }

    @Test
    void applyTitleNow_blankOrUnknownConversation_returnsFalse() {
        assertTrue(!titleService.applyTitleNow(1L, "  "));
        assertTrue(!titleService.applyTitleNow(null, "Some title here"));
        when(conversationRepository.findById(999L)).thenReturn(Optional.empty());
        assertTrue(!titleService.applyTitleNow(999L, "Some title here"));
    }
}
