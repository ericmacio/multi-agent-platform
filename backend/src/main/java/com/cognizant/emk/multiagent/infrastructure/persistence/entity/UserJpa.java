package com.cognizant.emk.multiagent.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserJpa {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Case-insensitive uniqueness is enforced by the functional unique index
    // ux_users_email_lower on lower(email) — see V004__email_case_insensitive.sql.
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "disabled", nullable = false)
    private boolean disabled;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UserJpa() {
    }

    public UserJpa(UUID id, String email, String passwordHash, String role,
                   boolean disabled, boolean mustChangePassword,
                   OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.disabled = disabled;
        this.mustChangePassword = mustChangePassword;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public boolean isDisabled() { return disabled; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    // Setter accepts the raw string as-is. Canonicalization to lowercase happens at the
    // domain boundary in Email; bypassing the value object here is loud, not silent.
    public void setEmail(String email) { this.email = email; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setRole(String role) { this.role = role; }
    public void setDisabled(boolean disabled) { this.disabled = disabled; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserJpa other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
