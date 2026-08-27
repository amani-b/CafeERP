package com.cafeerp.assistant;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.cafeerp.AbstractIntegrationTest;

/**
 * Regression tests for chat history logging on the REAL AI provider path.
 * <p>
 * The provider chain is configured with an env var that always exists
 * ({@code PATH}) so {@link ModelProvider#hasApiKey()} is true, and the
 * {@link ChatCompletionClient} HTTP seam is mocked to return a valid
 * completion — exercising the full tool-loop persistence code without any
 * network access.
 * <p>
 * The core assertion: after the HTTP response returns, BOTH sides of the
 * turn are already queryable in history (server-side persistence is
 * synchronous, never write-behind), and the question sorts before the reply.
 */
@SpringBootTest(properties = {
        // --- DB: H2 in PostgreSQL mode, running the real Flyway migrations ---
        "spring.datasource.url=jdbc:h2:mem:cafeerp_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        // --- AI provider ACTIVE but its HTTP seam is mocked below ---
        "assistant.providers[0].name=groq",
        "assistant.providers[0].baseUrl=https://api.invalid.invalid/v1",
        "assistant.providers[0].apiKeyEnvVar=PATH", // always set => hasApiKey() true
        "assistant.providers[0].model=test-model",
        "assistant.providers[0].supportsMinTokens=false",
        "logging.level.com.cafeerp=DEBUG"
})
@AutoConfigureMockMvc
public class AssistantLoggingTest extends AbstractIntegrationTest {

    @MockBean
    private ChatCompletionClient chatCompletionClient;

    @BeforeEach
    void stubProviderCompletion() throws Exception {
        when(chatCompletionClient.post(anyString(), anyString(), anyString()))
                .thenReturn(new ChatCompletionClient.Result(200,
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"AI-REPLY-FOR-LOGGING-TEST\"}}]}"));
    }

    private String postChat(MockHttpSession session, String message, Long conversationId) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("message", message);
        if (conversationId != null) {
            payload.put("conversationId", String.valueOf(conversationId));
        }
        return mockMvc.perform(post("/assistant/chat").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(payload)))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aiPathPersistsBothSidesImmediately_userBeforeReply() throws Exception {
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);

        // First turn (no explicit conversation — one is resolved/created).
        String firstReply = postChat(admin, "general question about beans one", null);
        assertThat(firstReply).contains("AI-REPLY-FOR-LOGGING-TEST");

        // Immediately query history — no settle time, no second turn needed.
        MvcResult history = mockMvc.perform(get("/assistant/history").session(admin))
                .andExpect(status().isOk()).andReturn();
        String body = history.getResponse().getContentAsString();
        assertThat(body)
                .as("user message must be persisted before the response is sent")
                .contains("general question about beans one");
        int userAt = body.indexOf("general question about beans one");
        int replyAt = body.indexOf("AI-REPLY-FOR-LOGGING-TEST");
        assertThat(userAt).as("question must sort before its reply in history").isLessThan(replyAt);
    }

    @Test
    void aiPathTurnLandsInRequestedConversation_andIsQueryableViaConversationEndpoint() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        // Create an explicit thread (what the "New chat" button does), then
        // chat into it and read it back through the conversation endpoint.
        MvcResult created = mockMvc.perform(
                        post("/assistant/conversations").session(staff)
                                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> convo = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                created.getResponse().getContentAsString(), Map.class);
        long conversationId = ((Number) convo.get("id")).longValue();

        postChat(staff, "ai path canary two", conversationId);

        String messages = mockMvc.perform(
                        get("/assistant/conversations/" + conversationId + "/messages").session(staff))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(messages).contains("ai path canary two").contains("AI-REPLY-FOR-LOGGING-TEST");

        // And the thread title was derived from the first user message.
        String list = mockMvc.perform(get("/assistant/conversations").session(staff))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("ai path canary two");
    }
}
