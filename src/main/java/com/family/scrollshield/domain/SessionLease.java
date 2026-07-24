package com.family.scrollshield.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "session_leases")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionLease {

    @Id
    @Column(nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private ViewingPlan plan;

    @Column(name = "lease_token", nullable = false, unique = true, length = 128)
    private String leaseToken;

    @Column(name = "node_id", nullable = false, length = 128)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LeaseStatus status;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt;

    @Column(name = "session_granted_seconds", nullable = false)
    private int sessionGrantedSeconds;

    @Column(name = "consumed_seconds_total", nullable = false)
    private int consumedSecondsTotal;

    @Column(name = "last_increment_seconds", nullable = false)
    private int lastIncrementSeconds;

    @Column(nullable = false)
    private long revision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.id == null) this.id = UUID.randomUUID();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) this.status = LeaseStatus.ACTIVE;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isActiveAt(Instant now, long expiryMarginSeconds) {
        if (status != LeaseStatus.ACTIVE) return false;
        return lastHeartbeatAt.plusSeconds(expiryMarginSeconds).isAfter(now);
    }
}
