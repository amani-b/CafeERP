package com.cafeerp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import javax.sql.DataSource;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base class for full-stack {@link MockMvc} integration tests.
 * <p>
 * Runs against an H2 database in PostgreSQL compatibility mode with the real
 * Flyway migrations applied, exercising the complete Spring MVC + Security
 * stack. All AI provider env vars are neutralized (via
 * {@code assistant.providers[0].apiKeyEnvVar}) so tests are hermetic — every
 * provider reports "no API key" and the assistant exercises its deterministic
 * code paths.
 */
@SpringBootTest(properties = {
        // --- DB: H2 in PostgreSQL mode, running the real Flyway migrations ---
        "spring.datasource.url=jdbc:h2:mem:cafeerp_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        // --- AI providers neutralized so tests are hermetic ---
        "assistant.providers[0].name=groq",
        "assistant.providers[0].baseUrl=https://api.invalid.invalid/v1",
        "assistant.providers[0].apiKeyEnvVar=CAFEERP_TEST_DEFINITELY_NOT_SET_9XQ",
        "assistant.providers[0].model=test-model",
        "assistant.providers[0].supportsMinTokens=false",
        // --- verbose diagnostics ---
        "logging.level.com.cafeerp=DEBUG"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
public abstract class AbstractIntegrationTest {

    protected static final String PLACEHOLDER_PASSWORD = "changeme123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected DataSource dataSource;

    /**
     * The seed users ship with must_change_password = TRUE, which makes the
     * PasswordChangeFilter redirect every request to /account/password. Clear
     * the flag once so tests exercise normal signed-in behaviour.
     */
    @BeforeAll
    void clearForcedPasswordFlagsAndNeutralizeAiKeys() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE cafe_user SET must_change_password = FALSE");
    }

    /** Log in through the real form-login filter and return the session. */
    protected MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(formLogin().user(username).password(password))
                .andExpect(authenticated())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /** Convenience wrapper for posting a chat message as a logged-in session. */
    protected MvcResult chat(MockHttpSession session, String message) throws Exception {
        return mockMvc.perform(post("/assistant/chat").session(session)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("message", message))))
                .andReturn();
    }

    protected static String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}