package com.cafeerp.settings;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.cafeerp.AbstractIntegrationTest;
import org.springframework.mock.web.MockHttpSession;

/**
 * Full-stack render test for the settings page: the timezone combobox
 * template (custom anchored dropdown replacing the native datalist) must
 * parse and render with the server-supplied timezone list, and the
 * timezone save flow must keep working.
 */
class SettingsPageIntegrationTest extends AbstractIntegrationTest {

    @Test
    void settingsPageRendersTimezoneCombobox() throws Exception {
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);

        mockMvc.perform(get("/settings").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"tz-combo\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"tz-combo-list\"")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("datalist"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"tz-options\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Africa/")));
    }

    @Test
    void timezoneSaveStillWorksWithoutDatalist() throws Exception {
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);

        mockMvc.perform(post("/settings/timezone").session(admin).with(csrf())
                        .param("timezone", "Europe/Paris"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/settings").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"Europe/Paris\"")));
    }
}