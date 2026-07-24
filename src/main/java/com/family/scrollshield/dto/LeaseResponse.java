package com.family.scrollshield.dto;

import java.time.Instant;
import java.util.UUID;

public record LeaseResponse(
        UUID leaseId,
        String leaseToken,
        UUID memberId,
        UUID planId,
        String status,
        Instant grantedAt,
        Instant expiresAt,
        Instant nextHeartbeatDueBy,
        int sessionGrantedSeconds,
        int consumedSecondsTotal,
        int dailyLimitSeconds,
        int extensionSeconds,
        int remainingSecondsToday
) {}
