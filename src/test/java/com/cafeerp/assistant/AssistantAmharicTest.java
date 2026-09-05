package com.cafeerp.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cafeerp.assistant.AssistantService.AssistantReply;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Amharic conversational support: the system prompt sent to every provider
 * must instruct fluency in Amharic — including Latin-transliterated Amharic —
 * with strict script matching, for every role tier, and a transliterated
 * message must flow through the chat pipeline untouched.
 */
@ExtendWith(MockitoExtension.class)
class AssistantAmharicTest {

    @Mock
    private AssistantMessageRepository messageRepository;
    @Mock
    private AssistantConversationRepository conversationRepository;
    @Mock
    private AssistantToolRegistry toolRegistry;
    @Mock
    private AssistantConfigProperties configProperties;
    @Mock
    private ChatCompletionClient chatCompletionClient;
    @Mock
    private DeterministicFallbackHandler fallbackHandler;
    @Mock
    private AssistantActionLogRepository actionLogRepository;

    private final AssistantAccessGuard accessGuard = new AssistantAccessGuard();
    private AssistantService assistantService;

    private User staffUser;
    private User kitchenUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        staffUser = new User("staff1", "pass", Role.STAFF);
        staffUser.setId(1L);
        kitchenUser = new User("kitchen1", "pass", Role.KITCHEN);
        kitchenUser.setId(2L);
        adminUser = new User("admin1", "pass", Role.ADMIN);
        adminUser.setId(3L);

        lenient().when(messageRepository.findByUserOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of());
        lenient().when(messageRepository.findByConversationOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of());
        lenient().when(conversationRepository
                        .findFirstByUserAndArchivedAtIsNullOrderByLastActivityAtDesc(any()))
                .thenReturn(java.util.Optional.empty());
        lenient().when(conversationRepository.save(any(AssistantConversation.class)))
                .thenAnswer(inv -> {
                    AssistantConversation c = inv.getArgument(0);
                    if (c.getId() == null) {
                        c.setId(4242L);
                    }
                    return c;
                });

        // One live provider (PATH is always set, so hasApiKey() is true).
        AssistantConfigProperties.ProviderConfig pc = new AssistantConfigProperties.ProviderConfig();
        pc.setName("test");
        pc.setBaseUrl("https://example.test/v1");
        pc.setApiKeyEnvVar("PATH");
        pc.setModel("test-model");
        pc.setSupportsMinTokens(false);
        lenient().when(configProperties.getProviders()).thenReturn(List.of(pc));

        lenient().when(toolRegistry.toolsForUser(any())).thenReturn(List.of());
        lenient().when(toolRegistry.allowedToolNamesForUser(any())).thenReturn(Set.of());

        assistantService = new AssistantService(messageRepository, conversationRepository,
                toolRegistry, new ObjectMapper(), chatCompletionClient, configProperties,
                fallbackHandler, accessGuard, actionLogRepository,
                org.mockito.Mockito.mock(AssistantTitleService.class));
    }

    private static ChatCompletionClient.Result ok(String content) throws Exception {
        String body = new ObjectMapper().writeValueAsString(
                Map.of("choices", List.of(Map.of("message",
                        Map.of("role", "assistant", "content", content)))));
        return new ChatCompletionClient.Result(200, body);
    }

    /** System prompt sent to the provider, for a given user. */
    private String systemPromptSentFor(User user, String message) throws Exception {
        when(chatCompletionClient.post(anyString(), anyString(), anyString()))
                .thenReturn(ok("Dehna, men yinesal?"));
        assistantService.processMessage(user, message);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(chatCompletionClient).post(anyString(), anyString(), body.capture());
        Map<?, ?> parsed = new ObjectMapper().readValue(body.getValue(), Map.class);
        List<?> messages = (List<?>) parsed.get("messages");
        return String.valueOf(((Map<?, ?>) messages.get(0)).get("content"));
    }

    @Test
    void systemPrompt_instructsAmharicFluency_forStaff() throws Exception {
        String prompt = systemPromptSentFor(staffUser, "selam, dehna neh?");
        assertTrue(prompt.contains("Amharic"), "must name Amharic fluency: " + prompt);
        assertTrue(prompt.contains("Latin"), "must cover Latin-transliterated Amharic: " + prompt);
        assertTrue(prompt.contains("match") && prompt.contains("script"),
                "must require script matching: " + prompt);
    }

    @Test
    void systemPrompt_instructsAmharicFluency_forKitchen() throws Exception {
        String prompt = systemPromptSentFor(kitchenUser, "selam");
        assertTrue(prompt.contains("Amharic"), "kitchen tier must get the same instruction");
        assertTrue(prompt.contains("Latin"), "kitchen tier must cover transliteration");
    }

    @Test
    void systemPrompt_instructsAmharicFluency_forAdmin() throws Exception {
        String prompt = systemPromptSentFor(adminUser, "Hello");
        assertTrue(prompt.contains("Amharic"), "admin tier must get the same instruction");
        assertTrue(prompt.contains("Latin"), "admin tier must cover transliteration");
    }

    @Test
    void transliteratedMessage_flowsThroughUntouched() throws Exception {
        when(chatCompletionClient.post(anyString(), anyString(), anyString()))
                .thenReturn(ok("Dehna negn, keza men atfelgaleh?"));
        AssistantReply reply = assistantService.processMessage(
                staffUser, "selam, dehna neh? order 482 yet laye?");
        assertEquals("Dehna negn, keza men atfelgaleh?", reply.text());
    }

    @Test
    void geezScriptMessage_flowsThroughUntouched() throws Exception {
        when(chatCompletionClient.post(anyString(), anyString(), anyString()))
                .thenReturn(ok("ሰላም! ደህና ነኝ, እንዴት ልርዳዎት?"));
        AssistantReply reply = assistantService.processMessage(staffUser, "ሰላም, ደህና ነህ?");
        assertEquals("ሰላም! ደህና ነኝ, እንዴት ልርዳዎት?", reply.text());
    }
}
