package com.family.scrollshield.dto;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventResponse(
        Long id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        Instant createdAt,
        boolean published
) {}
