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
        // The title backfill runs as a background timer that MUTATES
        // conversation rows; a scheduler firing mid-assertion makes tests
        // nondeterministic (seen on CI). Tests invoke the pass on demand
        // instead — production keeps the timer on (default true).
        "assistant.title.backfill-enabled=false",
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

    @Autowired
    protected org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Autowired(required = false)
    protected com.cafeerp.common.LoginAttemptService loginAttemptService;

    /**
     * The seed users ship with must_change_password = TRUE, which makes the
     * PasswordChangeFilter redirect every request to /account/password. Clear
     * the flag once so tests exercise normal signed-in behaviour.
     * <p>
     * Also re-seeds the known {@code changeme123} test logins: migration V17
     * locks the seeded accounts on real deployments, but the full test suite
     * logs in as admin/staff hundreds of times — re-hash them here so every
     * integration test runs against the real form-login stack unchanged.
     */
    @BeforeAll
    void clearForcedPasswordFlagsAndNeutralizeAiKeys() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE cafe_user SET must_change_password = FALSE");
        String hash = passwordEncoder.encode(PLACEHOLDER_PASSWORD);
        jdbc.update("UPDATE cafe_user SET password = ?, locked_until = NULL WHERE username IN ('admin','staff','kitchen')",
                hash);
    }

    @org.junit.jupiter.api.BeforeEach
    void resetLoginAbuseGuard() {
        // Each test starts with a clean throttling/lockout slate (the guard
        // is in-memory per IP / per username).
        if (loginAttemptService != null) {
            loginAttemptService.resetForTests();
        }
        // Belt and braces: a lockout row persisted by a previous test must
        // never leak into the next one — nor may assistant quota spend, which
        // shares the same H2 across the whole suite (e.g. a capped staff row
        // would otherwise poison unrelated assistant tests).
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            jdbc.update("UPDATE cafe_user SET locked_until = NULL WHERE locked_until IS NOT NULL");
        } catch (Exception ignored) {
            // Demo-profile tests run without the prod schema — nothing to clear.
        }
        try {
            jdbc.update("DELETE FROM assistant_daily_usage");
        } catch (Exception ignored) {
            // Table exists only after V17 / when the prod schema is present.
        }
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