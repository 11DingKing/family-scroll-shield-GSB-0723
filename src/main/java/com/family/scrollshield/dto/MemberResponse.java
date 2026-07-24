package com.family.scrollshield.dto;

import com.family.scrollshield.domain.AgeGroup;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

public record MemberResponse(
        UUID id,
        String externalId,
        String displayName,
        AgeGroup ageGroup,
        String timeZone,
        LocalTime bedtimeLocal,
        Instant createdAt
) {}
