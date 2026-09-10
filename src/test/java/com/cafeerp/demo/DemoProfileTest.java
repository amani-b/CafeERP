package com.cafeerp.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.cafeerp.category.CategoryRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * End-to-end proof for the {@code demo} profile, running the full MVC +
 * security stack with mock HTTP sessions standing in for visitor browsers:
 * <ul>
 *   <li>seeded demo accounts can log in (fixture + auth wiring);</li>
 *   <li>every demo flow works with zero API keys (assistant included);</li>
 *   <li>each session gets a private sandbox (orders created in one session
 *       are invisible — 404 — in another);</li>
 *   <li>admin/config screens are disabled (403) yet anonymous users still
 *       get the normal login redirect;</li>
 *   <li>the assistant budget caps at {@link DemoAssistantQuota#MAX_MESSAGES_PER_SESSION}.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        // Pin the throwaway demo datasource explicitly: @ActiveProfiles merges
        // with the file default profile, so never rely on profile-doc ordering
        // for the datasource in tests (production sets SPRING_PROFILES_ACTIVE
        // directly and is demo-only there).
        "spring.datasource.url=jdbc:h2:mem:cafedemo_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "assistant.title.backfill-enabled=false",
        "assistant.providers[0].apiKeyEnvVar=CAFEERP_TEST_DEFINITELY_NOT_SET_9XQ",
        "logging.level.com.cafeerp=INFO"
})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class DemoProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Test
    void health_isStatelessUnderDemoProfile() throws Exception {
        // The external pinger hits this anonymously every few minutes: it
        // must answer 200 without minting sessions or touching demo state.
        MvcResult result = mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UP")))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void demoBeans_replaceJpaRepositories() {
        // The injected repository is the session-scoped demo proxy, not JPA.
        CategoryRepository categories =
                mockMvc.getDispatcherServlet().getWebApplicationContext().getBean(CategoryRepository.class);
        assertThat(categories).isInstanceOf(DemoCategoryRepository.class);
    }

    @Test
    void retentionJob_isDisabledInDemo() {
        var context = mockMvc.getDispatcherServlet().getWebApplicationContext();
        assertThat(context.containsBean("assistantConversationArchivalJob")).isFalse();
    }

    @Test
    void datasource_isThrowawayH2() throws Exception {
        assertThat(dataSource.getConnection().getMetaData().getURL()).contains("cafedemo_test");
    }

    @Test
    void seededAccounts_canLogIn() throws Exception {
        login("demo-admin", DemoSeedData.DEMO_PASSWORD);
        login("demo-staff", DemoSeedData.DEMO_PASSWORD);
        login("demo-kitchen", DemoSeedData.DEMO_PASSWORD);
    }

    @Test
    void loginPage_showsDemoHint() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("demo-admin")));
    }

    @Test
    void demoFlows_workWithoutApiKeys() throws Exception {
        // Admin-tier showcase: menu, inventory and reports are ADMIN-gated in
        // production, so the portfolio visitor uses the demo-admin account.
        MockHttpSession admin = login("demo-admin", DemoSeedData.DEMO_PASSWORD);
        mockMvc.perform(get("/").session(admin)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Public demo sandbox")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Not in demo:")));
        mockMvc.perform(get("/menu").session(admin)).andExpect(status().isOk());        mockMvc.perform(get("/inventory").session(admin)).andExpect(status().isOk());
        mockMvc.perform(get("/reports").session(admin)).andExpect(status().isOk());

        MockHttpSession session = login("demo-staff", DemoSeedData.DEMO_PASSWORD);
        mockMvc.perform(get("/orders").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/orders/new").session(session)).andExpect(status().isOk());

        MockHttpSession kitchen = login("demo-kitchen", DemoSeedData.DEMO_PASSWORD);
        mockMvc.perform(get("/kitchen").session(kitchen)).andExpect(status().isOk());

        // Assistant answers from demo data with no API keys (deterministic path).
        MvcResult chat = mockMvc.perform(post("/assistant/chat").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"status of order 7\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(chat.getResponse().getContentAsString()).contains("Order #7");
    }

    @Test
    void assistant_streamingWorksInDemo() throws Exception {
        MockHttpSession session = login("demo-staff", DemoSeedData.DEMO_PASSWORD);

        // The widget streams first: the SSE turn must deliver a reply event
        // even though demo state is session-scoped and the work runs on a
        // pooled thread.
        MvcResult started = mockMvc.perform(post("/assistant/chat/stream").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"status of order 7\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                        .asyncStarted())
                .andReturn();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Order #7")));
    }

    @Test
    void demoQuota_endpointReflectsUsage() throws Exception {
        MockHttpSession session = login("demo-staff", DemoSeedData.DEMO_PASSWORD);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        String before = mockMvc.perform(get("/assistant/demo-quota").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(before).get("remaining").asInt())
                .isEqualTo(DemoAssistantQuota.MAX_MESSAGES_PER_SESSION);

        mockMvc.perform(post("/assistant/chat").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk());

        String after = mockMvc.perform(get("/assistant/demo-quota").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(after).get("remaining").asInt())
                .isEqualTo(DemoAssistantQuota.MAX_MESSAGES_PER_SESSION - 1);
    }

    @Test
    void sessions_getPrivateSandboxes() throws Exception {
        MockHttpSession sessionA = login("demo-staff", DemoSeedData.DEMO_PASSWORD);

        // New order in A's sandbox (seed holds 7 orders, so this becomes #8).
        mockMvc.perform(post("/orders").session(sessionA).with(csrf())
                        .param("quantities[2]", "1"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/orders/8").session(sessionA)).andExpect(status().isOk());

        // A second visitor starts from the seed: order #8 does not exist there,
        // while the seeded order #7 does.
        MockHttpSession sessionB = login("demo-staff", DemoSeedData.DEMO_PASSWORD);
        mockMvc.perform(get("/orders/8").session(sessionB)).andExpect(status().isNotFound());
        mockMvc.perform(get("/orders/7").session(sessionB)).andExpect(status().isOk());
    }

    @Test
    void adminScreens_areDisabledButLoginStillRequired() throws Exception {
        // Anonymous visitors get the normal login redirect (security runs first).
        mockMvc.perform(get("/users")).andExpect(status().is3xxRedirection());

        MockHttpSession session = login("demo-admin", DemoSeedData.DEMO_PASSWORD);
        mockMvc.perform(get("/users").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/settings").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/assistant").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/assistant/admin/1").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void assistant_capsAtFifteenMessagesPerSession() throws Exception {
        MockHttpSession session = login("demo-staff", DemoSeedData.DEMO_PASSWORD);

        String lastBody = null;
        for (int i = 1; i <= DemoAssistantQuota.MAX_MESSAGES_PER_SESSION + 1; i++) {
            MvcResult result = mockMvc.perform(post("/assistant/chat").session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"hello demo " + i + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            lastBody = result.getResponse().getContentAsString();
            if (i <= DemoAssistantQuota.MAX_MESSAGES_PER_SESSION) {
                assertThat(lastBody).doesNotContain("used all 15");
            }
        }
        assertThat(lastBody).contains("used all 15");
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(formLogin().user(username).password(password))
                .andExpect(authenticated())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
