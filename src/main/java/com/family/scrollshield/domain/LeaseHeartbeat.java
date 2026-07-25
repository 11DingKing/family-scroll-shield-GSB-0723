package com.family.scrollshield.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only log of every accepted heartbeat, capturing the increment applied
 * and the resulting cumulative consumption. Enables audit replay and diagnosis
 * of out-of-order or duplicate heartbeats.
 */
@Entity
@Table(name = "lease_heartbeats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaseHeartbeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "lease_id", nullable = false)
    private UUID leaseId;

    /** Server-observed timestamp when the heartbeat was accepted. */
    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    /** Client-reported wall clock, used only for skew diagnostics (never trusted for billing). */
    @Column(name = "client_now")
    private OffsetDateTime clientNow;

    @Column(name = "increment_seconds", nullable = false)
    private int incrementSeconds;

    @Column(name = "total_consumed", nullable = false)
    private int totalConsumed;

    @Column(name = "next_expires_at", nullable = false)
    private OffsetDateTime nextExpiresAt;
}
