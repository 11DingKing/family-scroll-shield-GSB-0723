package com.family.scrollshield.dto;

import java.time.Instant;
import java.util.UUID;

public record ExtensionResponse(
        UUID approvalId,
        UUID memberId,
        UUID planId,
        int grantedSeconds,
        Instant grantedAt,
        Instant expiresAt,
        boolean applied
) {}
