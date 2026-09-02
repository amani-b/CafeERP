package com.cafeerp.assistant;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.cafeerp.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;

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
 * Audit trail for every AI-initiated action (Phase 4), kept DISTINCT from the
 * conversation history: who initiated it, what tool, with what parameters,
 * whether it was user-confirmed or auto-executed, and the result.
 * <p>
 * The same table doubles as the durable pending-confirmation store: when a
 * write tool requires user approval the action is first persisted with
 * {@link Status#PENDING_CONFIRMATION}; the confirm/cancel endpoints then move
 * it to {@link Status#EXECUTED} / {@link Status#CANCELLED} (or
 * {@link Status#FAILED} when execution throws).
 */
@Entity
@Table(name = "assistant_action_log")
public class AssistantActionLog {

    public enum Status { PENDING_CONFIRMATION, EXECUTED, FAILED, CANCELLED }
    /** How the action was finalized. Null while pending or cancelled. */
    public enum TriggerMode { USER_CONFIRMED, AUTO_EXECUTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(nullable = false)
    private String tool;

    @Column(name = "params_json", columnDefinition = "TEXT")
    private String paramsJson;

    /** Human-readable summary of what the AI wants to do (confirmation card). */
    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_mode")
    private TriggerMode triggerMode;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public AssistantActionLog() {
    }

    public AssistantActionLog(User user, Long conversationId, String tool, String paramsJson,
                              String description, Status status) {
        this.user = user;
        this.conversationId = conversationId;
        this.tool = tool;
        this.paramsJson = paramsJson;
        this.description = description;
        this.status = status;
    }

    public void setUser(User user) { this.user = user; }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public TriggerMode getTriggerMode() { return triggerMode; }
    public void setTriggerMode(TriggerMode triggerMode) { this.triggerMode = triggerMode; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}