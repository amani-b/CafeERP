package com.cafeerp.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.cafeerp.AbstractIntegrationTest;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * End-to-end proof for the AI sidebar-title feature, through the real MVC +
 * Security + persistence stack (H2 with the real Flyway migrations) and the
 * REAL AssistantTitleService wired from {@code assistant.providers[0].*} —
 * the wiring that used to resolve to NULL and silently kill every title.
 *
 * <p>The {@link ChatCompletionClient} HTTP seam is mocked (the established
 * hermetic pattern in this repo): requests whose body carries the title
 * prompt ("Title:") get a small title completion; everything else is a chat
 * turn. No API keys are needed, no network is touched, and the agentic
 * tool-calling flow is exercised exactly as production configures it.
 *
 * <p>Coverage:
 * <ol>
 *   <li><b>Live:</b> a new conversation's first reply swaps the raw first
 *       message title for a real 3-6 word AI summary shortly after the turn
 *       — the exact generated title is asserted and logged.</li>
 *   <li><b>Backfill:</b> an existing raw-message-titled conversation is
 *       re-titled by the backfill pass, with before/after counts logged.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        // --- AI provider ACTIVE (HTTP seam mocked below) so the real
        // title pipeline runs exactly as production wiring builds it ---
        "assistant.providers[0].name=groq",
        "assistant.providers[0].baseUrl=https://api.invalid.invalid/v1",
        "assistant.providers[0].apiKeyEnvVar=PATH", // always set => hasApiKey() true
        "assistant.providers[0].model=test-model",
        "assistant.providers[0].supportsMinTokens=false",
        // NOTE: a subclass @SpringBootTest properties list REPLACES the base
        // class's, so the backfill-disable flag must be repeated here — the
        // backfill pass below is invoked ON DEMAND by these tests.
        "assistant.title.backfill-enabled=false",
        "logging.level.com.cafeerp=DEBUG"
})
public class AssistantTitleEndToEndTest extends AbstractIntegrationTest {

    private static final String LIVE_QUESTION =
            "We are slammed this morning — how do I keep the espresso queue moving?";
    private static final String LIVE_REPLY =
            "Batch your shots and keep the milk steaming while you pull.";
    private static final String LIVE_TITLE = "Espresso rush survival tips";

    private static final String BACKFILL_QUESTION = "Which syrups are we out of right now?";
    private static final String BACKFILL_REPLY = "Hazelnut is out; vanilla is low.";
    private static final String BACKFILL_TITLE = "Syrup stock shortage";

    @Autowired
    private AssistantTitleService titleService;

    @Autowired
    private AssistantConversationRepository conversationRepository;

    @Autowired
    private AssistantMessageRepository messageRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private ChatCompletionClient chatCompletionClient;

    @BeforeEach
    void stubProviderCompletions() throws Exception {
        // Title requests carry the "Title:" prompt and NO tools payload.
        when(chatCompletionClient.post(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String body = invocation.getArgument(2);
                    boolean titleCall = body.contains("Title:") && !body.contains("\"tools\"");
                    String content = titleCall
                            ? (body.contains("syrups") ? BACKFILL_TITLE : LIVE_TITLE)
                            : (body.contains("espresso queue") ? LIVE_REPLY : BACKFILL_REPLY);
                    return new ChatCompletionClient.Result(200,
                            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                                    + content + "\"}}]}");
                });
    }

    @Test
    void firstReply_swapsRawMessageTitleForAiSummary_live() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        // Explicitly start a NEW thread (what "New chat" does). A turn without
        // a conversationId would land in the user's MOST RECENT existing
        // conversation — and the shared test database may hold one from an
        // earlier test class, silently defeating the "new conversation"
        // premise of this test.
        MvcResult created = mockMvc.perform(post("/assistant/conversations").session(staff)
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> convo = new ObjectMapper().readValue(
                created.getResponse().getContentAsString(), Map.class);
        long conversationId = ((Number) convo.get("id")).longValue();

        // --- the turn itself: normal chat must keep working ---
        MvcResult chatResult = mockMvc.perform(post("/assistant/chat").session(staff)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("message", LIVE_QUESTION,
                                "conversationId", String.valueOf(conversationId)))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> reply = new ObjectMapper().readValue(
                chatResult.getResponse().getContentAsString(), Map.class);
        assertEquals(LIVE_REPLY, reply.get("text"),
                "the AI chat reply must be untouched by the title feature");
        assertEquals(conversationId, ((Number) reply.get("conversationId")).longValue(),
                "the turn must land in the explicitly requested thread");

        // --- title pipeline: raw first message -> real AI summary ---
        String before = titleOf(staff, conversationId);
        System.out.println("[title-e2e] LIVE before=\"" + before + "\"");
        String after = pollTitle(staff, conversationId, LIVE_TITLE, 10_000);
        System.out.println("[title-e2e] LIVE after=\"" + after + "\" (conversationId=" + conversationId + ")");
        assertEquals(LIVE_TITLE, after,
                "sidebar title must become the exact AI-generated 3-6 word summary");
        assertTrue(after.split("\\s+").length >= 3 && after.split("\\s+").length <= 6,
                "generated title must stay within the 3-6 word brief");
    }

    @Test
    void backfill_retitlesRawMessageConversations_withBeforeAfterCounts() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);
        User staffUser = userRepository.findByUsername("staff").orElseThrow();

        // An existing conversation still carrying its raw first-message title.
        AssistantConversation conversation = new AssistantConversation(staffUser);
        conversation.setTitle(AssistantConversation.deriveTitle(BACKFILL_QUESTION));
        conversation = conversationRepository.save(conversation);
        messageRepository.save(new AssistantMessage(
                staffUser, AssistantMessageRole.USER, BACKFILL_QUESTION, conversation));
        messageRepository.save(new AssistantMessage(
                staffUser, AssistantMessageRole.ASSISTANT, BACKFILL_REPLY, conversation));
        long conversationId = conversation.getId();

        String before = titleOf(staff, conversationId);
        assertEquals(AssistantConversation.deriveTitle(BACKFILL_QUESTION), before,
                "precondition: the stored title is the raw first message");

        int updatedThisRun = titleService.backfillTitles();
        String after = pollTitle(staff, conversationId, BACKFILL_TITLE, 10_000);
        System.out.println("[title-e2e] BACKFILL before=\"" + before + "\" after=\"" + after
                + "\" titledByDirectRun=" + updatedThisRun
                + " (conversationId=" + conversationId + ")");
        assertEquals(BACKFILL_TITLE, after,
                "backfill must swap the raw message title for the AI summary");
        // This direct run titles at least THIS conversation; the shared H2
        // database may hold other raw-titled conversations from earlier test
        // classes, which this continuous pass legitimately drains too.
        assertTrue(updatedThisRun >= 1,
                "the on-demand backfill pass must have titled the new conversation");
    }

    // ------------------------------ helpers ------------------------------

    /** Current sidebar title of one conversation via the real REST endpoint. */
    private String titleOf(MockHttpSession session, long conversationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/assistant/conversations").session(session))
                .andExpect(status().isOk())
                .andReturn();
        List<?> list = new ObjectMapper().readValue(
                result.getResponse().getContentAsString(), List.class);
        for (Object item : list) {
            Map<?, ?> conversation = (Map<?, ?>) item;
            if (((Number) conversation.get("id")).longValue() == conversationId) {
                Object title = conversation.get("title");
                return title == null ? null : title.toString();
            }
        }
        throw new AssertionError("conversation " + conversationId + " missing from history list");
    }

    /** Waits until the conversation's title reaches the expected summary. */
    private String pollTitle(MockHttpSession session, long conversationId,
                             String expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = titleOf(session, conversationId);
            if (expected.equals(last)) {
                return last;
            }
            Thread.sleep(200);
        }
        return last;
    }
}