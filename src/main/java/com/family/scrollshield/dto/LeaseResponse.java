package com.family.scrollshield.dto;

import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.SessionLease;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LeaseResponse(
        UUID leaseId,
        String leaseToken,
        UUID memberId,
        UUID planId,
        LeaseStatus status,
        OffsetDateTime grantedAt,
        OffsetDateTime expiresAt,
        int sessionGrantedSeconds,
        int consumedSecondsTotal,
        int planRemainingSeconds
) {
    public static LeaseResponse of(SessionLease lease, int planRemainingSeconds) {
        return new LeaseResponse(
                lease.getId(),
                lease.getLeaseToken(),
                lease.getMemberId(),
                lease.getPlanId(),
                lease.getStatus(),
                lease.getGrantedAt(),
                lease.getExpiresAt(),
                lease.getSessionGrantedSeconds(),
                lease.getConsumedSecondsTotal(),
                planRemainingSeconds
        );
    }
}
