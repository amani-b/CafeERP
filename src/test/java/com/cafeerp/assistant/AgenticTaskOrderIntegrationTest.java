package com.cafeerp.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.cafeerp.AbstractIntegrationTest;
import com.cafeerp.user.Permission;
import com.cafeerp.user.Role;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;
import com.cafeerp.user.UserService;

/**
 * Regression tests for multi-task agentic turns: when a single user message
 * requests SEVERAL tasks, every task must get its own confirmation card, in
 * ONE turn (no follow-up prompt needed), and the cards must appear in EXACTLY
 * the order the tasks were mentioned in the user's query — even when the
 * provider emits the batched tool calls out of order (the taskOrder sort).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cafeerp_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "assistant.providers[0].name=groq",
        "assistant.providers[0].baseUrl=https://api.invalid.invalid/v1",
        "assistant.providers[0].apiKeyEnvVar=PATH", // always set => hasApiKey() true
        "assistant.providers[0].model=test-model",
        "assistant.providers[0].supportsMinTokens=false",
        // NOTE: a subclass @SpringBootTest properties list REPLACES the base
        // class's, so the backfill-disable flag must be repeated here.
        "assistant.title.backfill-enabled=false"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AgenticTaskOrderIntegrationTest extends AbstractIntegrationTest {

    private static final String AGENTIC = "task_order_owner";

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;

    @MockBean
    private ChatCompletionClient chatCompletionClient;

    @BeforeAll
    void seedAgenticUser() {
        if (userRepository.findByUsername(AGENTIC).isEmpty()) {
            User user = new User();
            user.setUsername(AGENTIC);
            user.setPassword("password123");
            user.setRole(Role.ADMIN);
            userService.createUser(user);
        }
        User user = userRepository.findByUsername(AGENTIC).orElseThrow();
        user.setPermissions(Set.of(Permission.INVENTORY, Permission.AI_AGENTIC_ACTIONS));
        userService.updateUser(user);
        new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .update("UPDATE cafe_user SET must_change_password = FALSE");
    }

    /** Provider emits the three write calls REVERSED (taskOrder 3, 1, 2), batched in one response. */
    private static final String BATCHED_REVERSED_TOOL_CALLS =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                    + "{\"id\":\"call_3\",\"type\":\"function\",\"function\":{\"name\":\"updateInventory\","
                    + "\"arguments\":\"{\\\"itemName\\\":\\\"C Beans\\\",\\\"stockQuantity\\\":8,\\\"taskOrder\\\":3}\"}},"
                    + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"updateInventory\","
                    + "\"arguments\":\"{\\\"itemName\\\":\\\"A Beans\\\",\\\"stockQuantity\\\":3,\\\"taskOrder\\\":1}\"}},"
                    + "{\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"updateInventory\","
                    + "\"arguments\":\"{\\\"itemName\\\":\\\"B Beans\\\",\\\"stockQuantity\\\":5,\\\"taskOrder\\\":2}\"}}"
                    + "]}}]}";

    private static final String FINAL_REPLY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"All three tasks queued.\"}}]}";

    @Test
    void multiTaskTurn_queuesEveryTask_asCardsInUserStatedOrder() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        when(chatCompletionClient.post(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> providerCalls.incrementAndGet() == 1
                        ? new ChatCompletionClient.Result(200, BATCHED_REVERSED_TOOL_CALLS)
                        : new ChatCompletionClient.Result(200, FINAL_REPLY));

        MockHttpSession session = login(AGENTIC, "password123");
        MvcResult chatResult = mockMvc.perform(post("/assistant/chat").session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("message",
                                "set C Beans to 8, then A Beans to 3, then B Beans to 5"))))
                .andExpect(status().isOk())
                .andReturn();

        String body = chatResult.getResponse().getContentAsString();
        // All THREE tasks got their own card from this single turn...
        assertThat(countOccurrences(body, "\"tool\":\"updateInventory\"")).isEqualTo(3);
        // ...in the order the user mentioned them (A=3, B=5, C=8), NOT the
        // order the provider emitted the calls (C, A, B).
        assertThat(body.indexOf("\"stockQuantity\\\":3"))
                .as("first-mentioned task (A Beans -> 3) must be the first card")
                .isLessThan(body.indexOf("\"stockQuantity\\\":5"));
        assertThat(body.indexOf("\"stockQuantity\\\":5"))
                .isLessThan(body.indexOf("\"stockQuantity\\\":8"));

        // And the pending-actions reload endpoint keeps the same user-stated order.
        Long conversationId = extractConversationId(body);
        String reload = mockMvc.perform(get("/assistant/conversations/" + conversationId + "/pending-actions")
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(countOccurrences(reload, "\"tool\":\"updateInventory\"")).isEqualTo(3);
        assertThat(reload.indexOf("\"stockQuantity\\\":3"))
                .as("reload must keep the user-stated task order")
                .isLessThan(reload.indexOf("\"stockQuantity\\\":5"));
        assertThat(reload.indexOf("\"stockQuantity\\\":5")).isLessThan(reload.indexOf("\"stockQuantity\\\":8"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static Long extractConversationId(String replyBody) {
        try {
            Map<?, ?> map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(replyBody, Map.class);
            return ((Number) map.get("conversationId")).longValue();
        } catch (Exception e) {
            throw new IllegalStateException("No conversationId in reply: " + replyBody, e);
        }
    }
}
