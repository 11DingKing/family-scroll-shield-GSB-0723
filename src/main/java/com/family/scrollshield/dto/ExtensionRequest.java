package com.family.scrollshield.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ExtensionRequest(
        @NotNull UUID memberId,
        UUID leaseId,
        String approver,
        String reason
) {}
