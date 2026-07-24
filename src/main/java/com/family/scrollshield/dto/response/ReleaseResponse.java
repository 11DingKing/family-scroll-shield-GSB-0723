package com.family.scrollshield.dto.response;

import java.util.UUID;

public record ReleaseResponse(
        UUID leaseToken,
        String status,
        Integer consumedMinutes,
        Integer remainingDailyMinutes
) {}
