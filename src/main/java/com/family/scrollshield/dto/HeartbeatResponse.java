package com.family.scrollshield.dto;

import java.time.Instant;

public record HeartbeatResponse(
        String leaseToken,
        long revision,
        Instant expiresAt,
        Instant nextHeartbeatDueBy,
        int consumedSecondsTotal,
        int remainingSecondsToday,
        boolean mustStop
) {}
