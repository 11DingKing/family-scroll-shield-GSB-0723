package com.family.scrollshield.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AcquireLeaseRequest(
        @NotNull UUID memberId,
        @NotBlank String idempotencyKey,
        @NotNull @Min(1) Integer requestedMinutes,
        String deviceId
) {}
