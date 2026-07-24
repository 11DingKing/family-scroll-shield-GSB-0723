package com.family.scrollshield.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "lease_heartbeats")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaseHeartbeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lease_id", nullable = false)
    private SessionLease lease;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "client_now")
    private Instant clientNow;

    @Column(name = "increment_seconds", nullable = false)
    private int incrementSeconds;

    @Column(name = "total_consumed", nullable = false)
    private int totalConsumed;

    @Column(name = "next_expires_at", nullable = false)
    private Instant nextExpiresAt;
}
