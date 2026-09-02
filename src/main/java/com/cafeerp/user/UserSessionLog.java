package com.cafeerp.user;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One LOGIN or LOGOUT event for a user (Phase 4). Written by the Spring
 * Security login event listener and the custom logout success handler; read
 * by the assistant's agentic session-history tools. {@code username} is
 * snapshotted so history stays readable even if the account is later
 * soft-deleted.
 */
@Entity
@Table(name = "user_session_log")
public class UserSessionLog {

    public enum Event { LOGIN, LOGOUT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User user;

    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Event event;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    public void prePersist() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public UserSessionLog() {
    }

    public UserSessionLog(User user, Event event, String sessionId) {
        this.user = user;
        this.username = user.getUsername();
        this.event = event;
        this.sessionId = sessionId;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getUsername() { return username; }
    public Event getEvent() { return event; }
    public String getSessionId() { return sessionId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}