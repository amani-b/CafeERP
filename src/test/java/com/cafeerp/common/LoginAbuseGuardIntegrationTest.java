package com.cafeerp.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.cafeerp.AbstractIntegrationTest;
import com.cafeerp.user.User;
import com.cafeerp.user.UserRepository;
import com.cafeerp.user.UserSessionLog;
import com.cafeerp.user.UserSessionLogRepository;

/**
 * Credential-hardening login guard: per-IP rolling throttling (HTTP 429),
 * per-account lockout after consecutive bad passwords, LOGIN_FAILED audit,
 * and the generic (non-oracle) failure response.
 */
@Order(200)
class LoginAbuseGuardIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSessionLogRepository sessionLogRepository;

    @Test
    @org.junit.jupiter.api.Order(1)
    void throttling_eleventhRapidLoginGets429() throws Exception {
        String ip = "10.9.9.11";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/login")
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                            .header("X-Forwarded-For", ip)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("username", "no-such-user-" + i)
                            .param("password", "wrong"))
                    .andExpect(status().is3xxRedirection());
        }
        mockMvc.perform(post("/login")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", PLACEHOLDER_PASSWORD))
                .andExpect(status().is(429))
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void lockout_fiveBadPasswordsThenGoodPasswordRejectedUntilExpiry() throws Exception {
        // Five consecutive bad passwords lock the account.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(formLogin().user("staff").password("wrong-" + i));
        }
        User locked = userRepository.findByUsername("staff").orElseThrow();
        assertThat(locked.getLockedUntil()).isNotNull();
        assertThat(locked.getLockedUntil()).isAfter(LocalDateTime.now(ZoneOffset.UTC));

        // Correct password is ALSO rejected while locked — and identically
        // (same /login-error redirect as a wrong password: no lockout oracle).
        MvcResult lockedAttempt = mockMvc.perform(formLogin().user("staff").password(PLACEHOLDER_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(lockedAttempt.getResponse().getRedirectedUrl()).isEqualTo("/login-error");
        MvcResult badAttempt = mockMvc.perform(formLogin().user("staff").password("definitely-wrong"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(badAttempt.getResponse().getRedirectedUrl())
                .isEqualTo(lockedAttempt.getResponse().getRedirectedUrl());

        // Back-date the lockout: the correct password works again.
        locked.setLockedUntil(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        userRepository.save(locked);
        MockHttpSession session = login("staff", PLACEHOLDER_PASSWORD);
        assertThat(session).isNotNull();
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void failedLogin_isAuditedAsLoginFailed() {
        long before = sessionLogRepository.count();
        try {
            mockMvc.perform(formLogin().user("admin").password("wrong-password"));
        } catch (Exception ignored) {
            // formLogin result matchers throw on failure — the audit row is
            // what this test asserts; see below.
        }
        // The audit row is written through the real repository, so this
        // assertion covers both the write and its persistence.
        assertThat(sessionLogRepository.count()).isGreaterThanOrEqualTo(before + 1);
        boolean hasFailedRow = sessionLogRepository.findAll().stream()
                .anyMatch(row -> row.getEvent() == UserSessionLog.Event.LOGIN_FAILED
                        && "admin".equals(row.getUsername()));
        assertThat(hasFailedRow).isTrue();
        // Existing LOGIN/LOGOUT history queries are unaffected (new enum value only).
        assertThat(UserSessionLog.Event.valueOf("LOGIN")).isNotNull();
    }
}
