package com.family.scrollshield.dto;

import com.family.scrollshield.domain.AgeGroup;
import com.family.scrollshield.domain.Member;

import java.util.UUID;

public record MemberResponse(
        UUID id,
        String externalId,
        String displayName,
        AgeGroup ageGroup,
        String timeZone,
        String bedtimeLocal
) {
    public static MemberResponse from(Member m) {
        return new MemberResponse(
                m.getId(),
                m.getExternalId(),
                m.getDisplayName(),
                m.getAgeGroup(),
                m.getTimeZone(),
                m.getBedtimeLocal() == null ? null : m.getBedtimeLocal().toString()
        );
    }
}
