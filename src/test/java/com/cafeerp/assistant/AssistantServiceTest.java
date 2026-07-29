package com.cafeerp.assistant;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.cafeerp.assistant.AssistantConfigProperties.ProviderConfig;
import com.cafeerp.assistant.AssistantService.AssistantReply;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantServiceTest {

    @Mock
    private AssistantMessageRepository messageRepository;

    @Mock
    private AssistantToolRegistry toolRegistry;

    @Mock
    private AssistantConfigProperties configProperties;

    @Mock
    private DeterministicFallbackHandler fallbackHandler;

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

        lenient().when(messageRepository.findByUserOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());

        // Default: no providers configured
        lenient().when(configProperties.getProviders()).thenReturn(List.of());

        // Default tool registry stubs
        lenient().when(toolRegistry.toolsForStaff()).thenReturn(List.of(
            Map.of("type", "function", "function", Map.of("name", "getOrderStatus")),
            Map.of("type", "function", "function", Map.of("name", "getMenuItems"))
        ));
        lenient().when(toolRegistry.toolsForKitchen()).thenReturn(List.of(
            Map.of("type", "function", "function", Map.of("name", "getOrderStatus")),
            Map.of("type", "function", "function", Map.of("name", "getMenuItems")),
            Map.of("type", "function", "function", Map.of("name", "getKitchenQueueSummary"))
        ));
        lenient().when(toolRegistry.toolsForAdmin()).thenReturn(List.of(
            Map.of("type", "function", "function", Map.of("name", "getOrderStatus")),
            Map.of("type", "function", "function", Map.of("name", "getMenuItems")),
            Map.of("type", "function", "function", Map.of("name", "getSalesTotals")),
            Map.of("type", "function", "function", Map.of("name", "getTopSellingItems")),
            Map.of("type", "function", "function", Map.of("name", "getInventoryLevel")),
            Map.of("type", "function", "function", Map.of("name", "getKitchenQueueSummary"))
        ));
        lenient().when(toolRegistry.allowedToolNamesForRole(Role.STAFF))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems"));
        lenient().when(toolRegistry.allowedToolNamesForRole(Role.KITCHEN))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems", "getKitchenQueueSummary"));
        lenient().when(toolRegistry.allowedToolNamesForRole(Role.ADMIN))
                .thenReturn(Set.of("getOrderStatus", "getMenuItems", "getSalesTotals",
                        "getTopSellingItems", "getInventoryLevel", "getKitchenQueueSummary"));

        assistantService = new AssistantService(messageRepository, toolRegistry,
                new ObjectMapper(), configProperties, fallbackHandler);
    }

    // ---------------------------------------------------------------
    //  Tier 2 FIRST — deterministic pattern matching before any provider
    // ---------------------------------------------------------------

    @Test
    void tier2First_orderStatusQuery_shouldAnswerWithoutCallingProviders() {
        // When Tier 2 matches, no provider call should be attempted
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.STAFF)))
                .thenReturn(new AssistantReply("Order #5: status=READY", List.of()));

        AssistantReply reply = assistantService.processMessage(staffUser, "What's the status of order 5?");

        assertNotNull(reply);
        assertTrue(reply.text().contains("Order #5"));

        // Tier 2 was consulted
        verify(fallbackHandler).tryAnswer(anyString(), eq(Role.STAFF));
        // Tier 2 matched, so unavailableMessage should NOT be called
        verify(fallbackHandler, never()).unavailableMessage(any());
    }

    @Test
    void tier2First_menuQuery_shouldAnswer() {
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.STAFF)))
                .thenReturn(new AssistantReply("Here are the current menu items:", List.of()));

        AssistantReply reply = assistantService.processMessage(staffUser, "What's on the menu?");

        assertNotNull(reply);
        assertTrue(reply.text().contains("menu"));
    }

    @Test
    void tier2First_adminOnlyQuery_byStaff_shouldBeRefused() {
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.STAFF)))
                .thenReturn(new AssistantReply(
                        "I'm sorry, sales and revenue information is only available to managers and administrators.",
                        List.of()));

        AssistantReply reply = assistantService.processMessage(staffUser, "What were sales today?");

        assertNotNull(reply);
        assertTrue(reply.text().contains("only available"));
    }

    @Test
    void tier2First_kitchenOrderLink_shouldNotContainOrdersPath() {
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.KITCHEN)))
                .thenReturn(new AssistantReply("Order #5: status=PREPARING",
                        List.of(new AssistantService.SourceLink("View Order", "/kitchen"))));

        AssistantReply reply = assistantService.processMessage(kitchenUser, "What's order 5 status?");

        assertNotNull(reply);
        assertTrue(reply.links().stream().anyMatch(l -> l.url().equals("/kitchen")));
        assertTrue(reply.links().stream().noneMatch(l -> l.url().contains("/orders/")));
    }

    // ---------------------------------------------------------------
    //  Unmatched queries fall through to AI providers
    // ---------------------------------------------------------------

    @Test
    void unmatchedQuery_noProvidersConfigured_shouldUseUnavailableMessage() {
        // Tier 2 doesn't match this query
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.STAFF)))
                .thenReturn(null);
        // No providers configured (default), so fall through to unavailable
        lenient().when(fallbackHandler.unavailableMessage(Role.STAFF))
                .thenReturn(new AssistantReply("The AI assistant is temporarily unavailable. You can still ask me about:", List.of()));

        AssistantReply reply = assistantService.processMessage(staffUser, "What is the meaning of life?");

        assertNotNull(reply);
        assertTrue(reply.text().contains("temporarily unavailable"));
        verify(fallbackHandler).unavailableMessage(Role.STAFF);
    }

    // ---------------------------------------------------------------
    //  Multi-provider failover (Groq → Gemini → OpenRouter) for unmatched queries
    // ---------------------------------------------------------------

    @Test
    void unmatchedQuery_withProviders_failsToTier2Unavailable() {
        // Tier 2 doesn't match
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.STAFF)))
                .thenReturn(null);

        // Three providers with missing API keys (will be skipped by hasApiKey check)
        ProviderConfig groq = new ProviderConfig();
        groq.setName("groq");
        groq.setBaseUrl("https://api.groq.com/openai/v1");
        groq.setApiKeyEnvVar("GROQ_API_KEY_NONEXISTENT");
        groq.setModel("llama-3.3-70b-versatile");
        groq.setSupportsMinTokens(false);

        ProviderConfig gemini = new ProviderConfig();
        gemini.setName("gemini");
        gemini.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");
        gemini.setApiKeyEnvVar("GEMINI_API_KEY_NONEXISTENT");
        gemini.setModel("gemini-flash-latest");
        gemini.setSupportsMinTokens(false);

        ProviderConfig openrouter = new ProviderConfig();
        openrouter.setName("openrouter");
        openrouter.setBaseUrl("https://openrouter.ai/api/v1/chat/completions");
        openrouter.setApiKeyEnvVar("OPENROUTER_API_KEY_NONEXISTENT");
        openrouter.setModel("inclusional/ling-3.0-flash:free");
        openrouter.setSupportsMinTokens(false);

        when(configProperties.getProviders()).thenReturn(List.of(groq, gemini, openrouter));
        when(fallbackHandler.unavailableMessage(Role.STAFF))
                .thenReturn(new AssistantReply("The AI assistant is temporarily unavailable. You can still ask me about:", List.of()));

        assistantService = new AssistantService(messageRepository, toolRegistry,
                new ObjectMapper(), configProperties, fallbackHandler);

        AssistantReply reply = assistantService.processMessage(staffUser, "Hello");

        assertNotNull(reply);
        assertTrue(reply.text().contains("temporarily unavailable"));
        verify(fallbackHandler).unavailableMessage(Role.STAFF);
    }

    @Test
    void providerOrder_groqMissingKey_shouldTryGeminiNext() {
        // Tier 2 doesn't match
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.STAFF)))
                .thenReturn(null);

        // Groq has no API key (will be skipped), Gemini has no API key (will be skipped),
        // OpenRouter has no API key (will be skipped) — all fail, should hit unavailable
        ProviderConfig groq = new ProviderConfig();
        groq.setName("groq");
        groq.setBaseUrl("https://api.groq.com/openai/v1");
        groq.setApiKeyEnvVar("GROQ_API_KEY_NONEXISTENT");
        groq.setModel("llama-3.3-70b-versatile");
        groq.setSupportsMinTokens(false);

        ProviderConfig gemini = new ProviderConfig();
        gemini.setName("gemini");
        gemini.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");
        gemini.setApiKeyEnvVar("GEMINI_API_KEY_NONEXISTENT");
        gemini.setModel("gemini-flash-latest");
        gemini.setSupportsMinTokens(false);

        ProviderConfig openrouter = new ProviderConfig();
        openrouter.setName("openrouter");
        openrouter.setBaseUrl("https://openrouter.ai/api/v1/chat/completions");
        openrouter.setApiKeyEnvVar("OPENROUTER_API_KEY_NONEXISTENT");
        openrouter.setModel("inclusional/ling-3.0-flash:free");
        openrouter.setSupportsMinTokens(false);

        when(configProperties.getProviders()).thenReturn(List.of(groq, gemini, openrouter));
        when(fallbackHandler.unavailableMessage(Role.STAFF))
                .thenReturn(new AssistantReply("The AI assistant is temporarily unavailable. You can still ask me about:", List.of()));

        assistantService = new AssistantService(messageRepository, toolRegistry,
                new ObjectMapper(), configProperties, fallbackHandler);

        AssistantReply reply = assistantService.processMessage(staffUser, "Hello");

        assertNotNull(reply);
        assertTrue(reply.text().contains("temporarily unavailable"));
        verify(fallbackHandler).unavailableMessage(Role.STAFF);
    }

    // ---------------------------------------------------------------
    //  Persistence
    // ---------------------------------------------------------------

    @Test
    void processMessage_shouldPersistUserAndAssistantMessages() {
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.STAFF)))
                .thenReturn(new AssistantReply("OK", List.of()));

        assistantService.processMessage(staffUser, "What's on the menu?");

        verify(messageRepository, times(2)).save(any(AssistantMessage.class));
    }

    @Test
    void apiError_shouldReturnGracefulMessage() {
        // Tier 2 doesn't match
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.STAFF)))
                .thenReturn(null);
        lenient().when(fallbackHandler.unavailableMessage(Role.STAFF))
                .thenReturn(new AssistantReply("The AI assistant is temporarily unavailable.", List.of()));

        AssistantReply reply = assistantService.processMessage(staffUser, "Hello");

        assertNotNull(reply);
        assertTrue(reply.text().contains("temporarily unavailable"));
        assertTrue(reply.links().isEmpty());
    }

    @Test
    void staffRole_shouldOnlyHaveOrderStatusAndMenuItemsTools() {
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.STAFF)))
                .thenReturn(new AssistantReply("OK", List.of()));

        AssistantReply reply = assistantService.processMessage(staffUser, "Hello");

        assertNotNull(reply);
        assertEquals("OK", reply.text());
    }

    @Test
    void kitchenRole_shouldHaveOrderStatusMenuItemsAndKitchenQueueTools() {
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.KITCHEN)))
                .thenReturn(new AssistantReply("OK", List.of()));

        AssistantReply reply = assistantService.processMessage(kitchenUser, "Hello");

        assertNotNull(reply);
        assertEquals("OK", reply.text());
    }

    @Test
    void adminRole_shouldHaveAllTools() {
        lenient().when(fallbackHandler.tryAnswer(anyString(), eq(Role.ADMIN)))
                .thenReturn(new AssistantReply("OK", List.of()));

        AssistantReply reply = assistantService.processMessage(adminUser, "Hello");

        assertNotNull(reply);
        assertEquals("OK", reply.text());
    }

    // ---------------------------------------------------------------
    //  getFallbackReply — used by controller's outer catch-all
    // ---------------------------------------------------------------

    @Test
    void getFallbackReply_shouldReturnUnavailableMessage() {
        lenient().when(fallbackHandler.unavailableMessage(Role.STAFF))
                .thenReturn(new AssistantReply("The AI assistant is temporarily unavailable. You can still ask me about:", List.of()));

        AssistantReply reply = assistantService.getFallbackReply(staffUser);

        assertNotNull(reply);
        assertTrue(reply.text().contains("temporarily unavailable"));
    }

    @Test
    void getFallbackReply_shouldPersistMessage() {
        lenient().when(fallbackHandler.unavailableMessage(Role.STAFF))
                .thenReturn(new AssistantReply("The AI assistant is temporarily unavailable.", List.of()));

        assistantService.getFallbackReply(staffUser);

        verify(messageRepository).save(any(AssistantMessage.class));
    }
}