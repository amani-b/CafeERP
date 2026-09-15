package com.cafeerp.assistant;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One user's assistant spend for one UTC day. Backs the production cost
 * control ({@link AssistantDailyQuota}): each chat turn increments the row,
 * and turns past the daily allowance are refused before any provider call.
 */
@Entity
@Table(name = "assistant_daily_usage",
        uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "usage_day" }))
public class AssistantDailyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "usage_day", nullable = false)
    private LocalDate usageDay;

    @Column(nullable = false)
    private int turns = 0;

    public AssistantDailyUsage() {
    }

    public AssistantDailyUsage(Long userId, LocalDate usageDay) {
        this.userId = userId;
        this.usageDay = usageDay;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDate getUsageDay() {
        return usageDay;
    }

    public void setUsageDay(LocalDate usageDay) {
        this.usageDay = usageDay;
    }

    public int getTurns() {
        return turns;
    }

    public void setTurns(int turns) {
        this.turns = turns;
    }
}
