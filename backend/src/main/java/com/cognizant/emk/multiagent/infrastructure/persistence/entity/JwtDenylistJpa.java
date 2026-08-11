package com.cognizant.emk.multiagent.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "jwt_denylist")
public class JwtDenylistJpa {

    @Id
    @Column(name = "jti", nullable = false, updatable = false)
    private UUID jti;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected JwtDenylistJpa() {
    }

    public JwtDenylistJpa(UUID jti, OffsetDateTime expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
    }

    public UUID getJti() { return jti; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JwtDenylistJpa other)) return false;
        return Objects.equals(jti, other.jti);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jti);
    }
}
