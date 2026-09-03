package com.cafeerp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full-stack tests for the message action endpoints: regeneration
 * (/assistant/regenerate) and edit-and-resend (/assistant/edit).
 */
class AssistantRegenerateEditIntegrationTest extends AbstractIntegrationTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> threadMessages(MockHttpSession session, long conversationId)
            throws Exception {
        String json = mockMvc.perform(
                        get("/assistant/conversations/" + conversationId + "/messages").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
    }

    @Test
    void regenerate_answersWithoutDuplicatingTheUserQuery() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        // Seed one normal turn first.
        MvcResult seed = chat(staff, "Hello");
        assertThat(seed.getResponse().getStatus()).isEqualTo(200);
        Map<?, ?> seedReply = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                seed.getResponse().getContentAsString(), Map.class);
        long conversationId = ((Number) seedReply.get("conversationId")).longValue();

        int userMessagesBefore = 0;
        for (Map<String, Object> m : threadMessages(staff, conversationId)) {
            if ("USER".equals(m.get("role"))) userMessagesBefore++;
        }

        // Regenerate the reply to the SAME original query.
        MvcResult result = mockMvc.perform(post("/assistant/regenerate").session(staff)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of(
                                "message", "Hello",
                                "conversationId", String.valueOf(conversationId)))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<Map<String, Object>> messages = threadMessages(staff, conversationId);
        // The original query is NOT persisted again — user message count unchanged.
        int userMessagesAfter = 0;
        for (Map<String, Object> m : messages) {
            if ("USER".equals(m.get("role"))) userMessagesAfter++;
        }
        assertThat(userMessagesAfter).isEqualTo(userMessagesBefore);
        // The previous AI reply is REPLACED, not stacked: exactly one assistant
        // reply follows the query, and the thread length is unchanged.
        assertThat(messages.size()).isEqualTo(userMessagesAfter * 2);
        long assistantReplies = messages.stream()
                .filter(m -> "ASSISTANT".equals(m.get("role"))).count();
        assertThat(assistantReplies).isEqualTo(userMessagesAfter);
        assertThat(messages).anyMatch(m ->
                "ASSISTANT".equals(m.get("role")) && m.get("content") != null
                        && !String.valueOf(m.get("content")).isBlank());
    }

    @Test
    void edit_deletesOriginalTurn_andServicesEditedText() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        MvcResult seed = chat(staff, "What's on the menu?");
        assertThat(seed.getResponse().getStatus()).isEqualTo(200);
        Map<?, ?> seedReply = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                seed.getResponse().getContentAsString(), Map.class);
        long conversationId = ((Number) seedReply.get("conversationId")).longValue();

        MvcResult result = mockMvc.perform(post("/assistant/edit").session(staff)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of(
                                "originalMessage", "What's on the menu?",
                                "message", "Hello",
                                "conversationId", String.valueOf(conversationId)))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<Map<String, Object>> messages = threadMessages(staff, conversationId);
        String original = "What's on the menu?";
        boolean originalGone = messages.stream().noneMatch(m -> original.equals(m.get("content")));
        boolean editedPresent = messages.stream().anyMatch(m -> "Hello".equals(m.get("content")));
        boolean replyPresent = messages.stream().anyMatch(m ->
                "ASSISTANT".equals(m.get("role")) && m.get("content") != null
                        && !String.valueOf(m.get("content")).isBlank());
        assertThat(originalGone).as("original query and its replies must be deleted").isTrue();
        assertThat(editedPresent).as("edited query must be serviced").isTrue();
        assertThat(replyPresent).as("edited query must get a reply").isTrue();
    }
}