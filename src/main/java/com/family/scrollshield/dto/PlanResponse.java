package com.family.scrollshield.dto;

import java.time.LocalDate;
import java.util.UUID;

public record PlanResponse(
        UUID id,
        UUID memberId,
        LocalDate planDate,
        int dailyLimitSeconds,
        int consumedSeconds,
        int extensionSeconds,
        boolean extensionUsed,
        String status,
        int remainingSeconds
) {}
