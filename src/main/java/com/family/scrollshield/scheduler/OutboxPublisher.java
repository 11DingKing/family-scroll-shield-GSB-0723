package com.family.scrollshield.scheduler;

import com.family.scrollshield.domain.OutboxEvent;
import com.family.scrollshield.repository.OutboxEventRepository;
import com.family.scrollshield.service.QuotaPolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;

/**
 * Drains the transactional outbox and marks events published (at-least-once). In this
 * reference implementation "publishing" is logging; a real deployment would forward to
 * Kafka/SNS/etc. Because events are written in the same transaction as the authoritative
 * mutation, the published stream is a faithful, replayable audit log even across crashes
 * and Redis loss.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH = 100;

    private final OutboxEventRepository repository;
    private final QuotaPolicyService quotaPolicy;

    public OutboxPublisher(OutboxEventRepository repository, QuotaPolicyService quotaPolicy) {
        this.repository = repository;
        this.quotaPolicy = quotaPolicy;
    }

    @Scheduled(fixedDelayString = "${scrollshield.outbox.publish-interval-ms:5000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = repository.findUnpublished(PageRequest.of(0, BATCH));
        if (pending.isEmpty()) {
            return;
        }
        for (OutboxEvent event : pending) {
            // Placeholder for real transport. Idempotent downstreams keyed on event id.
            log.debug("Publishing outbox event id={} type={} aggregate={}:{}",
                    event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId());
            event.setPublished(true);
            event.setPublishedAt(quotaPolicy.now().atOffset(ZoneOffset.UTC));
        }
        repository.saveAll(pending);
    }
}
