package com.family.scrollshield.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RequestExtensionRequest(
        @NotNull UUID memberId,
        UUID leaseToken,
        @NotBlank String idempotencyKey,
        @NotNull @Min(1) @Max(5) Integer requestedMinutes,
        String reason
) {
    public RequestExtensionRequest {
        if (requestedMinutes == null) requestedMinutes = 5;
    }
}
