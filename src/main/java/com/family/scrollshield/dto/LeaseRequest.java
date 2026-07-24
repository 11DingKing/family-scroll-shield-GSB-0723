package com.family.scrollshield.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to acquire a session lease for a member. {@code clientRequestId} lets
 * clients retry safely without acquiring duplicate leases.
 */
public record LeaseRequest(
        @NotBlank String memberExternalId,
        String clientRequestId
) {
}
