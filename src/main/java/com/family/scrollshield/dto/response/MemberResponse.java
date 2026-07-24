package com.family.scrollshield.dto.response;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

public record MemberResponse(
        UUID memberId,
        String name,
        String role,
        String timezone,
        LocalTime bedtimeLocal,
        Integer dailyLimitMin,
        Integer singleLimitMin,
        Instant createdAt
) {}
