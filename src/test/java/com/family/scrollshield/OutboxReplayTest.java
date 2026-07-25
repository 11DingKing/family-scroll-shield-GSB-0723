package com.family.scrollshield;

import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.OutboxEvent;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.repository.OutboxEventRepository;
import com.family.scrollshield.service.AggregateTypes;
import com.family.scrollshield.service.EventTypes;
import com.family.scrollshield.service.LeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transactional outbox is the audit backbone: every authoritative state change writes
 * an event in the same transaction, giving a replayable, ordered history per aggregate.
 */
@SpringBootTest
@Import({TestFixtureFactory.class, LeaseTestSupport.class, TestClockConfig.class})
class OutboxReplayTest extends AbstractIntegrationTest {

    @Autowired
    LeaseService leaseService;
    @Autowired
    OutboxEventRepository outboxRepository;
    @Autowired
    TestFixtureFactory fixtures;
    @Autowired
    LeaseTestSupport support;

    @BeforeEach
    void reset() {
        CLOCK.setInstant(Instant.parse("2026-01-15T12:00:00Z"));
        support.flushRedis();
    }

    @Test
    void memberCreationRecordsEvent() {
        Member member = fixtures.adult("UTC");
        List<OutboxEvent> events =
                outboxRepository.findByAggregateTypeAndAggregateIdOrderByIdAsc(AggregateTypes.MEMBER, member.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(EventTypes.MEMBER_CREATED);
        assertThat(events.get(0).getPayload()).contains(member.getExternalId());
    }

    @Test
    void eventsAreRecordedForAuditReplay() {
        Member member = fixtures.adult("UTC");
        SessionLease lease = leaseService.acquire(member.getExternalId());
        CLOCK.advanceSeconds(60);
        leaseService.heartbeat(lease.getLeaseToken(), null);
        leaseService.release(lease.getLeaseToken());

        List<OutboxEvent> leaseEvents =
                outboxRepository.findByAggregateTypeAndAggregateIdOrderByIdAsc(AggregateTypes.LEASE, lease.getId());
        List<String> types = leaseEvents.stream().map(OutboxEvent::getEventType).toList();

        // The full lifecycle is captured in order: grant -> heartbeat -> terminate.
        assertThat(types).containsExactly(
                EventTypes.LEASE_GRANTED,
                EventTypes.HEARTBEAT,
                EventTypes.LEASE_TERMINATED);

        // Replaying (re-reading) yields identical, ordered events -> deterministic audit.
        assertThat(outboxRepository.findByAggregateId(lease.getId()))
                .extracting(OutboxEvent::getEventType)
                .containsExactly(EventTypes.LEASE_GRANTED, EventTypes.HEARTBEAT, EventTypes.LEASE_TERMINATED);
    }
}
