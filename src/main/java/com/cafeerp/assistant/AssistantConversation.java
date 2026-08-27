package com.cafeerp.assistant;

import java.time.LocalDateTime;

import com.cafeerp.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A single assistant chat conversation ("thread") belonging to one user.
 * <p>
 * Conversations power the chat UI's history sidebar: "New chat" creates one,
 * every message attaches to one, and the title is auto-derived from the first
 * user message. Conversations idle for {@code assistant.chat.archive-after-days}
 * (default 30) are soft-archived by a scheduled job — hidden from the default
 * history list but recoverable via the archived filter.
 */
@Entity
@Table(name = "assistant_conversation")
public class AssistantConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    // SECURITY/STABILITY: never serialize the lazy User proxy (same reasoning
    // as AssistantMessage.user) — history endpoints return DTOs instead.
    @JsonIgnore
    private User user;

    /**
     * Human-readable preview/title, auto-derived from the first user message
     * of the conversation (truncated). Null until the first message arrives.
     */
    @Column(length = 255)
    private String title;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** Bumped on every message so "most recent first" sorting is cheap. */
    @Column(nullable = false)
    private LocalDateTime lastActivityAt;

    /**
     * SOFT ARCHIVE: non-null means the conversation was auto-archived by the
     * scheduled job after {@code archive-after-days} of inactivity. Archived
     * conversations are excluded from the default history list but remain
     * queryable/recoverable — never deleted by the archive job.
     */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    public AssistantConversation() {
    }

    public AssistantConversation(User user) {
        this.user = user;
        this.createdAt = LocalDateTime.now();
        this.lastActivityAt = this.createdAt;
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastActivityAt == null) {
            lastActivityAt = createdAt;
        }
    }

    /** Keeps "most recent conversation first" correct without extra queries. */
    @PreUpdate
    public void preUpdate() {
        if (lastActivityAt == null) {
            lastActivityAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastActivityAt() {
        return lastActivityAt;
    }

    public void setLastActivityAt(LocalDateTime lastActivityAt) {
        this.lastActivityAt = lastActivityAt;
    }

    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    /**
     * Derive a conversation title from the first user message: first line,
     * collapsed whitespace, truncated on a word boundary with an ellipsis.
     */
    public static String deriveTitle(String firstUserMessage) {
        if (firstUserMessage == null) {
            return null;
        }
        String text = firstUserMessage.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        final int max = 60;
        if (text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf(' ', max);
        if (cut < max / 2) {
            cut = max;
        }
        return text.substring(0, cut).trim() + "\u2026";
    }
}