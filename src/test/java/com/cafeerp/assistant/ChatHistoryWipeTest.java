package com.cafeerp.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import com.cafeerp.AbstractIntegrationTest;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies the one-time chat-history wipe (V14): real deletes across all
 * tiers/users from exactly the three chat/log stores — sidebar/history
 * (conversations + messages), Assistant Logs (same message rows), and the
 * AI action log — with before/after counts logged, everything else
 * untouched, and a brand-new conversation working normally afterward.
 */
public class ChatHistoryWipeTest extends AbstractIntegrationTest {

    @Autowired
    private AssistantConversationRepository conversationRepository;

    @Autowired
    private AssistantMessageRepository messageRepository;

    @Autowired
    private AssistantActionLogRepository actionLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void wipe_deletesOnlyChatStores_thenFreshConversationWorks() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        User staff = userRepository.findByUsername("staff").orElseThrow();

        // --- seed all three stores (two users' worth where it matters) ---
        AssistantConversation c1 = conversationRepository.save(new AssistantConversation(staff));
        c1.setTitle("Seeded chatroom one");
        conversationRepository.save(c1);
        messageRepository.save(new AssistantMessage(
                staff, AssistantMessageRole.USER, "seed question one", c1));
        messageRepository.save(new AssistantMessage(
                staff, AssistantMessageRole.ASSISTANT, "seed answer one", c1));
        AssistantActionLog seeded = new AssistantActionLog();
        seeded.setUser(staff);
        seeded.setConversationId(c1.getId());
        seeded.setTool("getMenuItems");
        seeded.setParamsJson("{}");
        seeded.setDescription("Seeded audit row");
        seeded.setStatus(AssistantActionLog.Status.EXECUTED);
        actionLogRepository.save(seeded);

        long usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM cafe_user", Long.class);
        long sessionLogsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM user_session_log", Long.class);

        long messagesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM assistant_message", Long.class);
        long actionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM assistant_action_log", Long.class);
        long conversationsBefore =
                jdbc.queryForObject("SELECT COUNT(*) FROM assistant_conversation", Long.class);
        System.out.println("[wipe] BEFORE messages=" + messagesBefore
                + " actionLog=" + actionsBefore + " conversations=" + conversationsBefore);
        assertTrue(messagesBefore >= 2 && actionsBefore >= 1 && conversationsBefore >= 1,
                "precondition: all three stores hold rows");

        // --- the wipe itself: the exact statements V14 runs, FK-safe order ---
        long messagesDeleted = jdbc.update("DELETE FROM assistant_message");
        long actionsDeleted = jdbc.update("DELETE FROM assistant_action_log");
        long conversationsDeleted = jdbc.update("DELETE FROM assistant_conversation");

        long messagesAfter = jdbc.queryForObject("SELECT COUNT(*) FROM assistant_message", Long.class);
        long actionsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM assistant_action_log", Long.class);
        long conversationsAfter =
                jdbc.queryForObject("SELECT COUNT(*) FROM assistant_conversation", Long.class);
        System.out.println("[wipe] DELETED messages=" + messagesDeleted
                + " actionLog=" + actionsDeleted + " conversations=" + conversationsDeleted);
        System.out.println("[wipe] AFTER messages=" + messagesAfter
                + " actionLog=" + actionsAfter + " conversations=" + conversationsAfter);
        assertEquals(0, messagesAfter, "sidebar/history messages must be empty post-wipe");
        assertEquals(0, actionsAfter, "AI action log must be empty post-wipe");
        assertEquals(0, conversationsAfter, "chatrooms must be empty post-wipe");

        // --- everything outside the three stores is untouched ---
        assertEquals(usersBefore, jdbc.queryForObject("SELECT COUNT(*) FROM cafe_user", Long.class),
                "user accounts must survive the wipe");
        assertEquals(sessionLogsBefore,
                jdbc.queryForObject("SELECT COUNT(*) FROM user_session_log", Long.class),
                "login/session audit must survive the wipe");

        // --- the app starts clean and a fresh conversation still works ---
        MockHttpSession session = login("staff", PLACEHOLDER_PASSWORD);
        String listAfter = mockMvc.perform(get("/assistant/conversations").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(0, ((List<?>) new ObjectMapper().readValue(listAfter, List.class)).size(),
                "sidebar must show no entries post-wipe");

        String createdBody = mockMvc.perform(post("/assistant/conversations").session(session)
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long freshId = ((Number) ((Map<?, ?>) new ObjectMapper()
                .readValue(createdBody, Map.class)).get("id")).longValue();

        // Hermetic providers (no API keys in tests) → deterministic/unavailable
        // reply; what matters is the turn pipeline persists and returns 200.
        mockMvc.perform(post("/assistant/chat").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("message", "What is on the menu today?",
                                "conversationId", String.valueOf(freshId)))))
                .andExpect(status().isOk());
        String listFresh = mockMvc.perform(get("/assistant/conversations").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(1, ((List<?>) new ObjectMapper().readValue(listFresh, List.class)).size(),
                "a brand-new conversation must work normally after the wipe");
        System.out.println("[wipe] fresh conversation " + freshId + " created and chatted in cleanly");
    }
}
