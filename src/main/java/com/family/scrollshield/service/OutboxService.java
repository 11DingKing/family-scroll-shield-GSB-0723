package com.family.scrollshield.service;

import com.family.scrollshield.domain.OutboxEvent;
import com.family.scrollshield.domain.OutboxStatus;
import com.family.scrollshield.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final RestClient outboxRestClient;

    @Value("${app.outbox.webhook-url:}")
    private volatile String webhookUrl;

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Value("${app.outbox.max-retries:5}")
    private int maxRetries;

    @Value("${app.outbox.batch-size:100}")
    private int batchSize;

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
                .retryCount(0)
                .build();
        outboxEventRepository.save(event);
        log.debug("Outbox event recorded: {} {} for {}", eventType, event.getEventId(), aggregateId);
    }

    @Scheduled(fixedDelayString = "${app.outbox.polling-interval-ms:500}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findPendingEvents(batchSize);
        for (OutboxEvent event : events) {
            try {
                publishViaHttp(event);
                event.setStatus(OutboxStatus.SENT);
                event.setPublishedAt(Instant.now());
                log.info("Outbox event published: type={} id={} aggregate={}",
                        event.getEventType(), event.getEventId(), event.getAggregateId());
            } catch (Exception e) {
                int newRetryCount = event.getRetryCount() + 1;
                event.setRetryCount(newRetryCount);
                if (newRetryCount >= maxRetries) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("Outbox event permanently failed after {} retries: id={} error={}",
                            maxRetries, event.getEventId(), e.getMessage());
                } else {
                    log.warn("Outbox event publish failed (retry {}/{}): id={} error={}",
                            newRetryCount, maxRetries, event.getEventId(), e.getMessage());
                }
            }
        }
        outboxEventRepository.saveAll(events);
    }

    private void publishViaHttp(OutboxEvent event) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("OUTBOX_DISPATCH: type={} aggregate={} id={} payload={}",
                    event.getEventType(), event.getAggregateType(), event.getAggregateId(), event.getPayload());
            return;
        }

        OutboxWebhookPayload body = new OutboxWebhookPayload(
                event.getEventId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                event.getPayload(),
                event.getCreatedAt()
        );

        outboxRestClient.post()
                .uri(webhookUrl)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    public record OutboxWebhookPayload(
            UUID eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload,
            Instant timestamp
    ) {}
}
