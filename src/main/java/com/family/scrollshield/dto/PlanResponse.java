package com.family.scrollshield.dto;

import com.family.scrollshield.domain.PlanStatus;
import com.family.scrollshield.domain.ViewingPlan;

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
        int remainingSeconds,
        PlanStatus status
) {
    public static PlanResponse from(ViewingPlan p) {
        return new PlanResponse(
                p.getId(),
                p.getMemberId(),
                p.getPlanDate(),
                p.getDailyLimitSeconds(),
                p.getConsumedSeconds(),
                p.getExtensionSeconds(),
                p.isExtensionUsed(),
                p.remainingSeconds(),
                p.getStatus()
        );
    }
}
