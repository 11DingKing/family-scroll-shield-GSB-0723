package com.family.scrollshield.service;

import com.family.scrollshield.domain.OutboxEvent;
import com.family.scrollshield.domain.OutboxStatus;
import com.family.scrollshield.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvent(String aggregateType, String aggregateId, String eventType, Object payload) {
        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            jsonPayload = "{}";
            log.error("Failed to serialize outbox payload for {}:{}", aggregateType, eventType, e);
        }

        OutboxEvent event = OutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(jsonPayload)
                .status(OutboxStatus.PENDING)
                .build();
        event.setCreatedAt(Instant.now());
        outboxEventRepository.save(event);
        log.debug("Outbox event recorded: {} {} for {}", eventType, event.getEventId(), aggregateId);
    }

    @Scheduled(fixedDelayString = "${app.outbox.polling-interval-ms:500}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findPendingEvents(100);
        for (OutboxEvent event : events) {
            try {
                publish(event);
                event.setStatus(OutboxStatus.SENT);
                event.setPublishedAt(Instant.now());
                log.debug("Outbox event published: {} {}", event.getEventType(), event.getEventId());
            } catch (Exception e) {
                event.setStatus(OutboxStatus.FAILED);
                log.warn("Failed to publish outbox event {}: {}", event.getEventId(), e.getMessage());
            }
        }
        outboxEventRepository.saveAll(events);
    }

    private void publish(OutboxEvent event) {
        log.info("EVENT_DISPATCH: type={} aggregate={} id={} payload={}",
                event.getEventType(), event.getAggregateType(), event.getAggregateId(), event.getPayload());
    }
}
