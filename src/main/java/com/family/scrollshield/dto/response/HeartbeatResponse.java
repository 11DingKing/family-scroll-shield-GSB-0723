package com.family.scrollshield.dto.response;

import java.time.Instant;
import java.util.UUID;

public record HeartbeatResponse(
        UUID leaseToken,
        String status,
        Instant expiresAt,
        Instant serverTime,
        Long nextHeartbeatSeq,
        Integer remainingSeconds
) {}
