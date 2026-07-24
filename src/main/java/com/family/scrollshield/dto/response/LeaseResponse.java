package com.family.scrollshield.dto.response;

import java.time.Instant;
import java.util.UUID;

public record LeaseResponse(
        UUID leaseToken,
        UUID memberId,
        String status,
        Integer grantedMinutes,
        Instant startedAt,
        Instant expiresAt,
        Integer remainingDailyMinutes,
        Integer remainingSessionSeconds
) {}
