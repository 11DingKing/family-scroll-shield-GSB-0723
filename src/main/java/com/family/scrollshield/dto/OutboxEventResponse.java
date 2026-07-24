package com.family.scrollshield.dto;

import com.family.scrollshield.domain.OutboxEvent;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OutboxEventResponse(
        Long id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        OffsetDateTime createdAt,
        boolean published,
        OffsetDateTime publishedAt
) {
    public static OutboxEventResponse from(OutboxEvent e) {
        return new OutboxEventResponse(
                e.getId(),
                e.getAggregateType(),
                e.getAggregateId(),
                e.getEventType(),
                e.getPayload(),
                e.getCreatedAt(),
                e.isPublished(),
                e.getPublishedAt()
        );
    }
}
