package com.cafeerp.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import com.cafeerp.AbstractIntegrationTest;

/**
 * Bootstrap takeover defense (V17 + {@link BootstrapPasswordRunner}):
 * the publicly documented {@code changeme123} logins are locked on real
 * deployments, the first boot issues random passwords exactly once, and a
 * second boot is silent (idempotent — existing deployments untouched).
 */
class BootstrapPasswordRunnerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder testPasswordEncoder;

    @Autowired
    private BootstrapPasswordRunner runner;

    @AfterEach
    void restoreKnownTestLogins() {
        // Other integration tests share this context — never leak the locked
        // placeholder into them.
        String hash = testPasswordEncoder.encode(PLACEHOLDER_PASSWORD);
        new JdbcTemplate(dataSource).update(
                "UPDATE cafe_user SET password = ?, must_change_password = FALSE, locked_until = NULL"
                        + " WHERE username IN ('admin','staff','kitchen')",
                hash);
    }

    @Test
    void lockedPlaceholder_rejectsKnownPasswordLogin() throws Exception {
        lockSeedAccounts();
        MvcResult result = mockMvc.perform(formLogin().user("admin").password(PLACEHOLDER_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        // Generic failure — same /login-error redirect as any bad password.
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login-error");
    }

    @Test
    void runner_issuesWorkingRandomPassword_exactlyOnce() {
        lockSeedAccounts();

        String before = userRepository.findByUsername("admin").orElseThrow().getPassword();
        assertThat(before).isEqualTo(BootstrapPasswordRunner.LOCKED_PLACEHOLDER);

        runner.run(null);

        User after = userRepository.findByUsername("admin").orElseThrow();
        assertThat(after.getPassword()).isNotEqualTo(BootstrapPasswordRunner.LOCKED_PLACEHOLDER);
        assertThat(after.getPassword()).startsWith("$2");
        assertThat(after.isMustChangePassword()).isTrue();
        assertThat(after.getLockedUntil()).isNull();

        // Random per account — staff got a different hash than admin.
        User staff = userRepository.findByUsername("staff").orElseThrow();
        assertThat(staff.getPassword()).isNotEqualTo(BootstrapPasswordRunner.LOCKED_PLACEHOLDER);
        assertThat(staff.getPassword()).isNotEqualTo(after.getPassword());

        // Second boot is silent: hashes untouched, no rotation.
        String adminHash = after.getPassword();
        String staffHash = staff.getPassword();
        runner.run(null);
        assertThat(userRepository.findByUsername("admin").orElseThrow().getPassword()).isEqualTo(adminHash);
        assertThat(userRepository.findByUsername("staff").orElseThrow().getPassword()).isEqualTo(staffHash);
    }

    @Test
    void runner_leavesRealPasswordsAlone() {
        // Accounts already carrying a real BCrypt hash (the normal state for
        // existing deployments that changed their passwords) are untouched.
        String adminHash = userRepository.findByUsername("admin").orElseThrow().getPassword();
        assertThat(adminHash).startsWith("$2");
        runner.run(null);
        assertThat(userRepository.findByUsername("admin").orElseThrow().getPassword()).isEqualTo(adminHash);
    }

    private void lockSeedAccounts() {
        new JdbcTemplate(dataSource).update(
                "UPDATE cafe_user SET password = ?, must_change_password = TRUE WHERE username IN ('admin','staff','kitchen')",
                BootstrapPasswordRunner.LOCKED_PLACEHOLDER);
        userRepository.findByUsername("admin").orElseThrow();
    }
}
