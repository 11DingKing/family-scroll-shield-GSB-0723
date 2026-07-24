package com.family.scrollshield.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LeaseRequest(
        @NotNull UUID memberId,
        String clientId,
        String clientNow
) {}
