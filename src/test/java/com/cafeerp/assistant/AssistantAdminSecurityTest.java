package com.cafeerp.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import com.cafeerp.AbstractIntegrationTest;

/**
 * Proves the /assistant/admin REST surface is genuinely admin-only and never
 * leaks credential material, using real requests through the security filter
 * chain.
 */
class AssistantAdminSecurityTest extends AbstractIntegrationTest {

    @Test
    void staffCannotListUsersWithAssistantActivity() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        mockMvc.perform(get("/assistant/admin").session(staff))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListUsersWithAssistantActivity() throws Exception {
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);

        mockMvc.perform(get("/assistant/admin").session(admin))
                .andExpect(status().isOk());
    }

    @Test
    void assistantResponsesNeverContainPasswordHashes() throws Exception {
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);

        // Generate some chat history first so the endpoints have data to return.
        chat(staff, "status of order #1");

        String body = mockMvc.perform(get("/assistant/history").session(staff))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("$2a$", "$2b$", "$2y$");

        String adminBody = mockMvc.perform(get("/assistant/admin").session(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(adminBody).doesNotContain("$2a$", "$2b$", "$2y$");
    }
}