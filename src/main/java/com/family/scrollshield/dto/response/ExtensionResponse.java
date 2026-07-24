package com.family.scrollshield.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ExtensionResponse(
        UUID approvalToken,
        UUID memberId,
        String status,
        Integer requestedMinutes,
        Integer grantedMinutes,
        Instant requestedAt,
        Instant decidedAt,
        String reason
) {}
