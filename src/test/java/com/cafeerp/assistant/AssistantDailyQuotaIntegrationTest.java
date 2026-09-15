package com.cafeerp.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import com.cafeerp.AbstractIntegrationTest;

/**
 * Production cost control: durable per-user daily assistant turn budget.
 * Turns past the allowance are refused BEFORE any model call; the budget is
 * per-user (a second user is unaffected) and resets at UTC midnight.
 */
class AssistantDailyQuotaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AssistantDailyQuota quota;

    @Autowired
    private AssistantDailyUsageRepository usageRepository;

    @Test
    void fiftyFirstTurnIsCapped_secondUserUnaffected_andResetsAtUtcMidnight() throws Exception {
        MockHttpSession staff = login("staff", PLACEHOLDER_PASSWORD);
        com.cafeerp.user.User staffUser =
                new JdbcTemplate(dataSource).queryForObject("SELECT id FROM cafe_user WHERE username='staff'",
                        (rs, n) -> {
                            com.cafeerp.user.User u = new com.cafeerp.user.User();
                            u.setId(rs.getLong(1));
                            return u;
                        });
        Long staffId = staffUser.getId();

        // Spend the whole daily budget directly through the quota service.
        for (int i = 0; i < quota.getMaxTurnsPerDay(); i++) {
            assertThat(quota.tryConsume(staffId)).isTrue();
        }
        assertThat(quota.tryConsume(staffId)).isFalse();
        assertThat(quota.remaining(staffId)).isZero();

        // The 51st chat turn is capped: friendly refusal, no provider call.
        var capped = chat(staff, "one more question please").getResponse().getContentAsString();
        assertThat(capped).contains("today's assistant allowance");

        // A different user still has a full budget.
        MockHttpSession admin = login("admin", PLACEHOLDER_PASSWORD);
        var ok = chat(admin, "hello admin turn").getResponse().getContentAsString();
        assertThat(ok).doesNotContain("today's assistant allowance");

        // Yesterday's spend never counts toward today.
        usageRepository.findByUserIdAndUsageDay(staffId, LocalDate.now(ZoneOffset.UTC))
                .ifPresent(row -> {
                    row.setUsageDay(LocalDate.now(ZoneOffset.UTC).minusDays(1));
                    usageRepository.save(row);
                });
        assertThat(quota.remaining(staffId)).isEqualTo(quota.getMaxTurnsPerDay());
        assertThat(quota.tryConsume(staffId)).isTrue();
    }
}
