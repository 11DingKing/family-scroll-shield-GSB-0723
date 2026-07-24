package com.family.scrollshield;

import static org.assertj.core.api.Assertions.assertThat;

import com.family.scrollshield.domain.Member;
import com.family.scrollshield.dto.LeaseRequest;
import com.family.scrollshield.dto.LeaseResponse;
import com.family.scrollshield.dto.MemberRequest;
import com.family.scrollshield.dto.OutboxEventResponse;
import com.family.scrollshield.service.AggregateTypes;
import com.family.scrollshield.service.EventTypes;
import com.family.scrollshield.service.LeaseService;
import com.family.scrollshield.service.MemberService;
import com.family.scrollshield.service.OutboxService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxReplayTest extends AbstractIntegrationTest {

    @Autowired
    private MemberService memberService;

    @Autowired
    private LeaseService leaseService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private TestFixtureFactory fixtures;

    @Test
    void eventsAreRecordedForAuditReplay() {
        Member adult = fixtures.createAdult("UTC");
        LeaseResponse lease = leaseService.acquire(new LeaseRequest(adult.getId(), "c1", null));

        List<OutboxEventResponse> events = outboxService.replay(AggregateTypes.LEASE, lease.leaseId());
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).eventType()).isEqualTo(EventTypes.LEASE_GRANTED);
        assertThat(events.get(0).aggregateId()).isEqualTo(lease.leaseId());
        assertThat(events.get(0).payload()).contains("\"leaseId\"");
    }

    @Test
    void memberCreationRecordsEvent() {
        var resp = memberService.create(new MemberRequest(
                "ext-123", "Alice", com.family.scrollshield.domain.AgeGroup.ADULT, "UTC", null));
        List<OutboxEventResponse> events = outboxService.replay(AggregateTypes.MEMBER, resp.id());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo(EventTypes.MEMBER_CREATED);
    }
}
