package com.family.scrollshield.dto;

import com.family.scrollshield.domain.ExtensionApproval;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ExtensionResponse(
        UUID approvalId,
        UUID memberId,
        UUID planId,
        int grantedSeconds,
        OffsetDateTime grantedAt,
        OffsetDateTime expiresAt,
        int planRemainingSeconds
) {
    public static ExtensionResponse of(ExtensionApproval a, int planRemainingSeconds) {
        return new ExtensionResponse(
                a.getId(),
                a.getMemberId(),
                a.getPlanId(),
                a.getGrantedSeconds(),
                a.getGrantedAt(),
                a.getExpiresAt(),
                planRemainingSeconds
        );
    }
}
