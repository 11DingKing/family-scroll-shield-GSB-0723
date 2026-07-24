package com.family.scrollshield.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.family.scrollshield.domain.OutboxEvent;
import com.family.scrollshield.dto.OutboxEventResponse;
import com.family.scrollshield.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(json)
                    .createdAt(Instant.now(clock))
                    .published(false)
                    .build();
            outboxRepository.save(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }

    @Transactional(readOnly = true)
    public List<OutboxEventResponse> replay(String aggregateType, UUID aggregateId) {
        return outboxRepository
                .findByAggregateTypeAndAggregateIdOrderByIdAsc(aggregateType, aggregateId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public List<OutboxEvent> pollForPublish(int batch) {
        List<OutboxEvent> events = outboxRepository.findUnpublished(batch);
        return events;
    }

    @Transactional
    public void markPublished(List<Long> ids) {
        if (ids.isEmpty()) return;
        outboxRepository.markPublished(ids, Instant.now(clock));
    }

    private OutboxEventResponse toResponse(OutboxEvent e) {
        return new OutboxEventResponse(
                e.getId(),
                e.getAggregateType(),
                e.getAggregateId(),
                e.getEventType(),
                e.getPayload(),
                e.getCreatedAt(),
                e.isPublished()
        );
    }
}
