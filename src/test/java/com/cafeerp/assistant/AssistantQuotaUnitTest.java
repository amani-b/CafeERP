package com.cafeerp.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.cafeerp.user.BootstrapPasswordRunner;

/**
 * Unit coverage for the quota + bootstrap helpers that need no web stack:
 * midnight rollover, cap size, and the bootstrap random-password format.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quota_unit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "assistant.providers[0].name=groq",
        "assistant.providers[0].baseUrl=https://api.invalid.invalid/v1",
        "assistant.providers[0].apiKeyEnvVar=CAFEERP_TEST_DEFINITELY_NOT_SET_9XQ",
        "assistant.providers[0].model=test-model",
        "assistant.title.backfill-enabled=false",
        "assistant.quota.turns-per-day=3"
})
class AssistantQuotaUnitTest {

    @org.springframework.beans.factory.annotation.Autowired
    private AssistantDailyQuota quota;

    @org.springframework.beans.factory.annotation.Autowired
    private AssistantDailyUsageRepository usageRepository;

    @Test
    void consumesUpToCap_thenRefuses_andYesterdayNeverCounts() {
        Long userId = 4242L;
        assertThat(quota.getMaxTurnsPerDay()).isEqualTo(3);
        assertThat(quota.tryConsume(userId)).isTrue();
        assertThat(quota.tryConsume(userId)).isTrue();
        assertThat(quota.tryConsume(userId)).isTrue();
        assertThat(quota.tryConsume(userId)).isFalse();
        assertThat(quota.remaining(userId)).isZero();

        // A heavy yesterday does not touch today's budget.
        AssistantDailyUsage yesterday = new AssistantDailyUsage(777L, LocalDate.now(ZoneOffset.UTC).minusDays(1));
        yesterday.setTurns(1000);
        usageRepository.save(yesterday);
        assertThat(quota.remaining(777L)).isEqualTo(3);
    }

    @Test
    void bootstrapRandomPassword_isLongUrlSafeAndUnique() {
        String a = BootstrapPasswordRunner.randomPassword();
        String b = BootstrapPasswordRunner.randomPassword();
        assertThat(a).hasSize(32);
        assertThat(b).hasSize(32);
        assertThat(a).isNotEqualTo(b);
        assertThat(a).matches("[A-Za-z0-9_-]+");
        assertThat(BootstrapPasswordRunner.LOCKED_PLACEHOLDER).isEqualTo("{locked}bootstrap-required");
    }
}
