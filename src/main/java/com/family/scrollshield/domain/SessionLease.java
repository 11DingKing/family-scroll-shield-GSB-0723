package com.family.scrollshield.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A session lease represents the single active viewing slot a member may hold.
 * The partial unique index {@code idx_one_active_lease_per_member} guarantees at
 * most one ACTIVE lease per member across all service instances.
 */
@Entity
@Table(name = "session_leases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionLease {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    /** Opaque token handed to the client; required to heartbeat or release. */
    @Column(name = "lease_token", nullable = false, unique = true)
    private String leaseToken;

    /** Id of the service instance that currently owns the lease. */
    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LeaseStatus status;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    /** Wall-clock deadline after which the lease may be taken over if no heartbeat arrives. */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private OffsetDateTime lastHeartbeatAt;

    /** Seconds granted for this session at acquire time (bounded by single-session limit). */
    @Column(name = "session_granted_seconds", nullable = false)
    private int sessionGrantedSeconds;

    /** Seconds already settled into the plan from this lease (idempotency anchor). */
    @Column(name = "consumed_seconds_total", nullable = false)
    private int consumedSecondsTotal;

    @Column(name = "last_increment_seconds", nullable = false)
    private int lastIncrementSeconds;

    /**
     * Monotonic revision bumped on each state transition. Used as a lightweight
     * concurrency / ordering guard and mirrored into the Redis cache entry.
     */
    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public boolean isActive() {
        return status == LeaseStatus.ACTIVE;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
