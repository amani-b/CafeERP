package com.cafeerp.assistant;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production cost control for the AI assistant: a durable per-user daily
 * turn budget. Every chat turn consumes one turn of the caller's UTC-day
 * allowance <i>before</i> any model provider is contacted, so refused turns
 * cost nothing. This is a soft spend cap, not a security boundary — under
 * exact concurrency two simultaneous turns may both pass; the overage is at
 * most a turn or two and never compounds.
 * <p>
 * The demo profile intentionally bypasses this (it has its own per-session
 * budget via {@code DemoAssistantQuota}; see {@code AssistantService}). The
 * {@code !demo} profile gate keeps the bean (and its JPA repository) out of
 * the demo application context, where the JPA layer is a throwaway H2 with
 * no quota tables.
 */
@Service
@Profile("!demo")
public class AssistantDailyQuota {

    private static final Logger log = LoggerFactory.getLogger(AssistantDailyQuota.class);

    private final AssistantDailyUsageRepository usageRepository;
    private final int maxTurnsPerDay;

    public AssistantDailyQuota(AssistantDailyUsageRepository usageRepository,
                               @Value("${assistant.quota.turns-per-day:50}") int maxTurnsPerDay) {
        this.usageRepository = usageRepository;
        this.maxTurnsPerDay = maxTurnsPerDay;
    }

    /**
     * Consumes one turn of the user's daily allowance. Returns {@code true}
     * when the turn may proceed, {@code false} once the day's budget is spent.
     */
    @Transactional
    public boolean tryConsume(Long userId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        AssistantDailyUsage row = usageRepository.findByUserIdAndUsageDay(userId, today)
                .orElseGet(() -> new AssistantDailyUsage(userId, today));
        if (row.getTurns() >= maxTurnsPerDay) {
            log.info("Assistant daily budget spent for user id={} ({} turns)", userId, row.getTurns());
            return false;
        }
        row.setTurns(row.getTurns() + 1);
        usageRepository.save(row);
        return true;
    }

    /** Turns remaining in the user's current UTC-day budget. */
    @Transactional(readOnly = true)
    public int remaining(Long userId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int used = usageRepository.findByUserIdAndUsageDay(userId, today)
                .map(AssistantDailyUsage::getTurns)
                .orElse(0);
        return Math.max(0, maxTurnsPerDay - used);
    }

    public int getMaxTurnsPerDay() {
        return maxTurnsPerDay;
    }
}
