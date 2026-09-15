package com.cafeerp.assistant;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantDailyUsageRepository extends JpaRepository<AssistantDailyUsage, Long> {

    Optional<AssistantDailyUsage> findByUserIdAndUsageDay(Long userId, LocalDate usageDay);

    /** Used by the admin hard-delete: the FK references cafe_user. */
    void deleteByUserId(Long userId);
}
