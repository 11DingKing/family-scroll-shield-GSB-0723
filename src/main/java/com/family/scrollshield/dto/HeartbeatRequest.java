package com.family.scrollshield.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/**
 * A heartbeat proving the session is still active. The server bills elapsed
 * server time between heartbeats; {@code clientNow} is kept only for skew
 * diagnostics and is never trusted for billing.
 */
public record HeartbeatRequest(
        @NotBlank String leaseToken,
        OffsetDateTime clientNow
) {
}
