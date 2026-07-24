package com.family.scrollshield.dto;

import com.family.scrollshield.domain.LeaseStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record HeartbeatResponse(
        UUID leaseId,
        LeaseStatus status,
        OffsetDateTime expiresAt,
        int incrementSeconds,
        int consumedSecondsTotal,
        int planConsumedSeconds,
        int planRemainingSeconds,
        boolean quotaExhausted
) {
}
