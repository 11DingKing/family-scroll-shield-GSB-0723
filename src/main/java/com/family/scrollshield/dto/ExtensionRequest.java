package com.family.scrollshield.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to approve a bedtime-safe daily extension (max one 5-minute grant per
 * plan). The approver is a caregiver identity; the grant is clamped so it can
 * never push viewing past the member's bedtime blackout window.
 */
public record ExtensionRequest(
        @NotBlank String memberExternalId,
        @NotBlank String approver,
        String reason
) {
}
