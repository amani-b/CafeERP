package com.cafeerp.user;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;

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