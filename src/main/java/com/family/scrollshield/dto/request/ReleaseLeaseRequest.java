package com.family.scrollshield.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReleaseLeaseRequest(
        @NotNull UUID leaseToken,
        @NotBlank String idempotencyKey
) {}
