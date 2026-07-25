package com.family.scrollshield.dto;

import com.family.scrollshield.domain.AgeGroup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request to register a family member. {@code bedtimeLocal} is a member-local
 * "HH:mm" time and is required for TEEN members (bedtime blackout enforcement).
 */
public record MemberRequest(
        @NotBlank String externalId,
        @NotBlank String displayName,
        @NotNull AgeGroup ageGroup,
        @NotBlank String timeZone,
        String bedtimeLocal
) {
}
