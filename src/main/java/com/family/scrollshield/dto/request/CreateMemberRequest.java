package com.family.scrollshield.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

public record CreateMemberRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank String role,
        String timezone,
        LocalTime bedtimeLocal,
        Integer dailyLimitMin,
        Integer singleLimitMin
) {
    public CreateMemberRequest {
        if (timezone == null) timezone = "UTC";
    }
}
