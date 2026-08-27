package com.cafeerp.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.cafeerp.AbstractIntegrationTest;

/**
 * Full-stack tests for the chat conversation API (history sidebar/overlay)
 * and the scheduled 30-day auto-archival job.
 */
class AssistantConversationTest extends AbstractIntegrationTest {

    @Autowired AssistantConversationArchivalJob archivalJob;
    @Autowired AssistantConversationRepository conversationRepository;

    private long sendChat(MockHttpSession session, String message) throws Exception {
        MvcResult result = chat(session, message);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // The turn always lands in the user's current conversation.
        var list = mockMvc.perform(get("/assistant/conversations").session(session))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> convo = newestConversation(list.getResponse().getContentAsString());
        return ((Number) convo.get("id")).longValue();
    }

    /** Starts a fresh thread ("New chat") and returns its id. */
    private long startNewConversation(MockHttpSession session) throws Exception {
        MvcResult created = mockMvc.perform(post("/assistant/conversations")
                        .session(session).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> convo = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                created.getResponse().getContentAsString(), Map.class);
        return ((Number) convo.get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> newestConversation(String json) throws Exception {
        List<Map<String, Object>> list = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, List.class);
        assertThat(list).isNotEmpty();
        return list.get(0); // most recent activity first
    }

    @Test
    void conversationIsAutoTitledFromFirstMessage_andOrdersMessagesCorrectly() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        // Fresh thread so the title is derived from THIS test's message.
        long conversationId = startNewConversation(staff);
        sendChat(staff, "What is on the menu today?");

        // Title auto-derived from the first user message.
        String listJson = mockMvc.perform(get("/assistant/conversations").session(staff))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listJson).contains("What is on the menu today?");

        // Both sides of the turn are queryable immediately, question first.
        String messagesJson = mockMvc.perform(
                        get("/assistant/conversations/" + conversationId + "/messages").session(staff))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(messagesJson)
                .contains("What is on the menu today?")
                .contains("Here are the current menu items");
        int userAt = messagesJson.indexOf("What is on the menu today?");
        int replyAt = messagesJson.indexOf("Here are the current menu items");
        assertThat(userAt).as("user question must sort before the assistant reply").isLessThan(replyAt);
    }

    @Test
    void newChatCreatesSeparateThread_andSwitchingKeepsThreadsIsolated() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        long first = startNewConversation(staff);
        sendChat(staff, "status of order #123456");

        // "New chat" — a fresh thread that does not lose the previous one.
        long second = startNewConversation(staff);
        assertThat(second).isNotEqualTo(first);

        long secondTurn = sendChat(staff, "status of order #654321");
        assertThat(secondTurn).isEqualTo(second); // current conversation is reused

        // History list: most recent first, both threads present.
        String listJson = mockMvc.perform(get("/assistant/conversations").session(staff))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listJson).contains("status of order #123456").contains("status of order #654321");
        assertThat(listJson.indexOf(String.valueOf(second)))
                .as("most recently active conversation sorts first")
                .isLessThan(listJson.indexOf(String.valueOf(first)));

        // Switching back: each thread only contains its own messages.
        String firstThread = mockMvc.perform(
                        get("/assistant/conversations/" + first + "/messages").session(staff))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(firstThread).contains("#123456").doesNotContain("#654321");
    }

    @Test
    void usersCannotReadOtherUsersConversations() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);
        startNewConversation(staff);
        long staffConversation = sendChat(staff, "hello there");

        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);
        mockMvc.perform(get("/assistant/conversations/" + staffConversation + "/messages").session(admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void scheduledJobArchivesIdleConversations_hiddenButRecoverable() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);
        startNewConversation(staff);
        long conversationId = sendChat(staff, "archival canary message");

        // Simulate 31 days of inactivity directly in the database.
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                "UPDATE assistant_conversation SET last_activity_at = ? WHERE id = ?",
                java.time.LocalDateTime.now().minusDays(31), conversationId);

        int archived = archivalJob.archiveStaleConversations();
        assertThat(archived).isGreaterThanOrEqualTo(1);

        // Hidden from the default history list...
        String defaultList = mockMvc.perform(get("/assistant/conversations").session(staff))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(defaultList).doesNotContain("archival canary message");

        // ...but recoverable via the archived filter.
        String archivedList = mockMvc.perform(
                        get("/assistant/conversations").session(staff).param("archived", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(archivedList).contains("archival canary message");

        // Archived conversations are still readable (soft archive, not deletion).
        mockMvc.perform(get("/assistant/conversations/" + conversationId + "/messages").session(staff))
                .andExpect(status().isOk());
    }
}
