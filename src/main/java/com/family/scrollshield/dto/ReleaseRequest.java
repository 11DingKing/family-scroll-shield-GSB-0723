package com.family.scrollshield.dto;

import jakarta.validation.constraints.NotBlank;

public record ReleaseRequest(
        @NotBlank String leaseToken
) {
}
