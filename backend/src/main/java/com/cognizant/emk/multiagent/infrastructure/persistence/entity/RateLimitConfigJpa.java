package com.cognizant.emk.multiagent.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "rate_limit_config")
public class RateLimitConfigJpa {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Short id;

    @Column(name = "per_minute", nullable = false)
    private int perMinute;

    @Column(name = "per_hour", nullable = false)
    private int perHour;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private UserJpa updatedBy;

    protected RateLimitConfigJpa() {
    }

    public RateLimitConfigJpa(Short id, int perMinute, int perHour,
                              OffsetDateTime updatedAt, UserJpa updatedBy) {
        this.id = id;
        this.perMinute = perMinute;
        this.perHour = perHour;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public Short getId() { return id; }
    public int getPerMinute() { return perMinute; }
    public int getPerHour() { return perHour; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public UserJpa getUpdatedBy() { return updatedBy; }

    public void setPerMinute(int perMinute) { this.perMinute = perMinute; }
    public void setPerHour(int perHour) { this.perHour = perHour; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public void setUpdatedBy(UserJpa updatedBy) { this.updatedBy = updatedBy; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RateLimitConfigJpa other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
