package com.family.scrollshield.dto;

import com.family.scrollshield.domain.AgeGroup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalTime;

public record MemberRequest(
        @NotBlank String externalId,
        @NotBlank String displayName,
        @NotNull AgeGroup ageGroup,
        @NotBlank @Pattern(regexp = "^[A-Za-z_]+/[A-Za-z_0-9-]+$") String timeZone,
        LocalTime bedtimeLocal
) {}
