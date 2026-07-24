package com.family.scrollshield.scheduler;

import com.family.scrollshield.domain.OutboxEvent;
import com.family.scrollshield.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxRepository;
    private final Clock clock;

    @Value("${scrollshield.outbox.retention-days:30}")
    private int retentionDays;

    @Scheduled(fixedDelayString = "${scrollshield.outbox.publish-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.findUnpublished(100);
        if (batch.isEmpty()) return;
        for (OutboxEvent e : batch) {
            log.info("Outbox event published: type={} aggregate={} id={}",
                    e.getEventType(), e.getAggregateType(), e.getAggregateId());
        }
        List<Long> ids = batch.stream().map(OutboxEvent::getId).toList();
        outboxRepository.markPublished(ids, Instant.now(clock));
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeOld() {
        Instant threshold = Instant.now(clock).minus(retentionDays, ChronoUnit.DAYS);
        int n = outboxRepository.deletePublishedBefore(threshold);
        if (n > 0) log.info("Purged {} old outbox events", n);
    }
}
