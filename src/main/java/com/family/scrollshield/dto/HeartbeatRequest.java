package com.family.scrollshield.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public record HeartbeatRequest(
        @NotBlank String leaseToken,
        @Min(0) int observedSecondsElapsed,
        Instant clientNow
) {}
