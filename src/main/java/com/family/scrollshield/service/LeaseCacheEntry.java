package com.family.scrollshield.service;

import com.family.scrollshield.domain.SessionLease;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lossable projection of an active lease held in Redis to accelerate the common
 * "is there an active lease?" and heartbeat paths. It is never authoritative:
 * PostgreSQL always wins, and every entry can be rebuilt from the database.
 */
public record LeaseCacheEntry(
        UUID leaseId,
        UUID memberId,
        UUID planId,
        String leaseToken,
        String nodeId,
        OffsetDateTime expiresAt,
        int consumedSecondsTotal,
        long revision
) implements Serializable {

    public static LeaseCacheEntry from(SessionLease lease) {
        return new LeaseCacheEntry(
                lease.getId(),
                lease.getMemberId(),
                lease.getPlanId(),
                lease.getLeaseToken(),
                lease.getNodeId(),
                lease.getExpiresAt(),
                lease.getConsumedSecondsTotal(),
                lease.getRevision()
        );
    }
}
