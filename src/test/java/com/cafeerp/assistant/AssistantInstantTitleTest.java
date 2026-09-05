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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.cafeerp.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Proves single-round-trip titles are genuinely instant — identically for
 * every tier (admin, staff, kitchen).
 *
 * <p>The mocked provider answers the chat turn with a reply that already
 * carries the {@code <title>} tag (what a real model does under the
 * first-turn title instruction). The test then asserts, with a SINGLE
 * immediate read (no polling, no backfill):
 * <ol>
 *   <li>the reply delivered to the user contains NO tag;</li>
 *   <li>the sidebar already shows the exact AI-generated 3–6 word title;</li>
 *   <li>no second (title-prompt) provider call was ever made;</li>
 *   <li>the persisted assistant message contains no tag.</li>
 * </ol>
 * The reply-confirmed → title-appeared delta is logged per tier.
 */
@SpringBootTest(properties = {
        "assistant.providers[0].name=groq",
        "assistant.providers[0].baseUrl=https://api.invalid.invalid/v1",
        "assistant.providers[0].apiKeyEnvVar=PATH",
        "assistant.providers[0].model=test-model",
        "assistant.providers[0].supportsMinTokens=false",
        "assistant.title.backfill-enabled=false",
        "logging.level.com.cafeerp=DEBUG"
})
public class AssistantInstantTitleTest extends AbstractIntegrationTest {

    private static final String QUESTION =
            "We are slammed this morning — how do I keep the espresso queue moving?";
    private static final String VISIBLE_REPLY =
            "Batch your shots and keep the milk steaming while you pull.";
    private static final String EXPECTED_TITLE = "Espresso rush survival tips";

    @Autowired
    private AssistantMessageRepository messageRepository;

    @Autowired
    private AssistantConversationRepository conversationRepository;

    @MockBean
    private ChatCompletionClient chatCompletionClient;

    private final AtomicInteger titlePromptCalls = new AtomicInteger();

    @BeforeEach
    void stubSingleRoundTripCompletion() throws Exception {
        titlePromptCalls.set(0);
        when(chatCompletionClient.post(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String body = invocation.getArgument(2);
                    boolean titleCall = body.contains("Title:") && !body.contains("\"tools\"");
                    if (titleCall) {
                        titlePromptCalls.incrementAndGet();
                        return new ChatCompletionClient.Result(200,
                                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                                        + "SHOULD NEVER BE CALLED\"}}]}");
                    }
                    // Chat turn: the model follows the first-turn instruction
                    // and ends its reply with the delimited title tag.
                    // (Escaped for JSON: a raw newline would be malformed.)
                    String content = VISIBLE_REPLY + "\\n<title>" + EXPECTED_TITLE + "</title>";
                    return new ChatCompletionClient.Result(200,
                            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                                    + content + "\"}}]}");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "staff", "kitchen"})
    void firstReply_titleIsInstantForEveryTier(String username) throws Exception {
        MockHttpSession session = login(username, PLACEHOLDER_PASSWORD);

        MvcResult created = mockMvc.perform(post("/assistant/conversations").session(session)
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andReturn();
        long conversationId = ((Number) new ObjectMapper().readValue(
                created.getResponse().getContentAsString(), Map.class).get("id")).longValue();

        long beforeSend = System.currentTimeMillis();
        MvcResult chatResult = mockMvc.perform(post("/assistant/chat").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("message", QUESTION,
                                "conversationId", String.valueOf(conversationId)))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> reply = new ObjectMapper().readValue(
                chatResult.getResponse().getContentAsString(), Map.class);
        long replyConfirmedAt = System.currentTimeMillis();

        String replyText = String.valueOf(reply.get("text"));
        assertTrue(!replyText.contains("<title>"),
                "[" + username + "] the tag must never reach the user: " + replyText);
        assertEquals(VISIBLE_REPLY, replyText,
                "[" + username + "] the visible reply must be untouched apart from tag stripping");

        // SINGLE immediate read — no polling, no backfill: the title must
        // already be there, in the same window as the reply.
        String title = titleOf(session, conversationId);
        long titleAppearedAt = System.currentTimeMillis();
        long deltaMs = titleAppearedAt - replyConfirmedAt;
        System.out.println("[instant-title] tier=" + username
                + " title=\"" + title + "\" replyToTitleDeltaMs=" + deltaMs
                + " turnTotalMs=" + (titleAppearedAt - beforeSend)
                + " (conversationId=" + conversationId + ")");
        assertEquals(EXPECTED_TITLE, title,
                "[" + username + "] sidebar must show the AI summary instantly");
        assertTrue(title.split("\\s+").length >= 3 && title.split("\\s+").length <= 6,
                "[" + username + "] title must stay within the 3-6 word brief: " + title);
        assertEquals(0, titlePromptCalls.get(),
                "[" + username + "] no second title round-trip may fire on the instant path");

        // The persisted message must be clean too.
        AssistantConversation conversation =
                conversationRepository.findById(conversationId).orElseThrow();
        List<AssistantMessage> messages = messageRepository
                .findByConversationOrderByCreatedAtAscIdAsc(conversation);
        assertTrue(messages.stream()
                        .filter(m -> m.getRole() == AssistantMessageRole.ASSISTANT)
                        .allMatch(m -> !m.getContent().contains("<title>")),
                "[" + username + "] persisted messages must not store the tag");
    }

    @Test
    void secondTurn_doesNotRetitle() throws Exception {
        MockHttpSession session = login("staff", PLACEHOLDER_PASSWORD);
        MvcResult created = mockMvc.perform(post("/assistant/conversations").session(session)
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andReturn();
        long conversationId = ((Number) new ObjectMapper().readValue(
                created.getResponse().getContentAsString(), Map.class).get("id")).longValue();

        mockMvc.perform(post("/assistant/chat").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("message", QUESTION,
                                "conversationId", String.valueOf(conversationId)))))
                .andExpect(status().isOk());
        assertEquals(EXPECTED_TITLE, titleOf(session, conversationId));

        // Second turn: the mock appends the tag again, but the thread is
        // already titled — the tag must still be stripped and the original
        // title left alone (exactly once per conversation's lifetime).
        MvcResult second = mockMvc.perform(post("/assistant/chat").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("message", "And what about decaf?",
                                "conversationId", String.valueOf(conversationId)))))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> reply = new ObjectMapper().readValue(
                second.getResponse().getContentAsString(), Map.class);
        assertTrue(!String.valueOf(reply.get("text")).contains("<title>"),
                "tag must be stripped on later turns too");
        assertEquals(EXPECTED_TITLE, titleOf(session, conversationId),
                "the first summary title must survive later turns");
    }

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
}
