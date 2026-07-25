package com.family.scrollshield.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.family.scrollshield.domain.OutboxEvent;
import com.family.scrollshield.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes state-change events into the transactional outbox. Records are always
 * persisted within the caller's transaction, so an event is durable if and only
 * if the authoritative mutation it describes committed. This is the backbone of
 * both at-least-once publication and audit replay.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Record an event inside the current transaction. MANDATORY propagation makes it a
     * programming error to record an event outside a transaction, which would break the
     * outbox guarantee.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(serialize(payload))
                .published(false)
                .build();
        return repository.save(event);
    }

    private String serialize(Object payload) {
        if (payload == null) {
            return "{}";
        }
        if (payload instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }
}
