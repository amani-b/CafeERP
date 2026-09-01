package com.cafeerp.user;

import java.time.LocalDateTime;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "cafe_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Username is required")
    @Column(nullable = false, unique = true)
    private String username;

    // SECURITY: The BCrypt hash must never be serialized to clients. This entity
    // is returned directly by the /assistant/admin REST endpoints, where Jackson
    // would otherwise include the hash in the JSON response. The Thymeleaf form
    // binding used by the users CRUD pages does not go through Jackson, so this
    // annotation has no effect there.
    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @NotNull(message = "Role is required")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean mustChangePassword = false;

    /**
     * Granular admin permissions (Phase 3). Only meaningful for ADMIN-tier
     * accounts; SUPER_ADMIN implicitly holds every permission and STAFF /
     * KITCHEN are governed by role rules. Persisted as a join table
     * ({@code cafe_user_permission}) so new permissions can be added without
     * touching the user row or running destructive migrations.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cafe_user_permission", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<Permission> permissions = new HashSet<>();

    /**
     * SOFT DELETE: when non-null the account is deactivated — it cannot log in
     * and is hidden from default user lists — but the row and every FK that
     * references it (e.g. assistant_message.user_id) stay intact. Null means
     * active. See {@code V9__add_user_soft_delete.sql}.
     */
    @JsonIgnore
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public User() {
    }

    public User(String username, String password, Role role) {
        this.username = username;
        this.password = password;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Role getRole() {
        return role;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<Permission> permissions) {
        // Always a mutable copy: Hibernate mutates managed element collections
        // in place on flush, and immutable sets (Set.of / Set.copyOf) blow up.
        this.permissions = permissions == null ? new HashSet<>() : new HashSet<>(permissions);
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    /** True when this account has been soft-deleted (deactivated). */
    @JsonIgnore
    public boolean isDeleted() {
        return deletedAt != null;
    }
}