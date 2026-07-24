package com.family.scrollshield.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public record DailyUsageResponse(
        UUID memberId,
        LocalDate usageDate,
        Integer dailyLimitMin,
        Integer usedMinutes,
        Integer remainingMinutes,
        Integer extensionsUsed
) {}
