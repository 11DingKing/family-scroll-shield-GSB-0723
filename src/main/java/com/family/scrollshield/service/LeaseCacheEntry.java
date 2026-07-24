package com.family.scrollshield.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record LeaseCacheEntry(
        UUID leaseId,
        UUID memberId,
        String leaseToken,
        Instant expiresAt,
        long revision
) {
    public Duration ttlFrom(Instant now, Duration margin) {
        Duration ttl = Duration.between(now, expiresAt).plus(margin);
        if (ttl.isNegative()) ttl = Duration.ofMillis(1);
        return ttl;
    }
}
