package com.family.scrollshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.dto.HeartbeatRequest;
import com.family.scrollshield.dto.HeartbeatResponse;
import com.family.scrollshield.dto.LeaseRequest;
import com.family.scrollshield.dto.LeaseResponse;
import com.family.scrollshield.service.LeaseException;
import com.family.scrollshield.service.LeaseService;
import com.family.scrollshield.service.QuotaPolicyService;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HeartbeatIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private LeaseService leaseService;

    @Autowired
    private TestFixtureFactory fixtures;

    @Autowired
    private LeaseTestSupport testSupport;

    @Autowired
    private Clock clock;

    @Test
    void duplicateHeartbeatDoesNotDoubleConsume() throws Exception {
        Member adult = fixtures.createAdult("UTC");
        LeaseResponse lease = leaseService.acquire(
                new LeaseRequest(adult.getId(), "c1", null));

        Thread.sleep(2000);

        HeartbeatRequest hb = new HeartbeatRequest(lease.leaseToken(), 0, Instant.now(clock));
        HeartbeatResponse r1 = leaseService.heartbeat(hb);
        int consumedAfterFirst = r1.consumedSecondsTotal();
        long revAfterFirst = r1.revision();

        HeartbeatResponse r2 = leaseService.heartbeat(hb);
        assertThat(r2.consumedSecondsTotal()).isEqualTo(consumedAfterFirst);
        assertThat(r2.revision()).isEqualTo(revAfterFirst);

        Thread.sleep(3000);
        HeartbeatResponse r3 = leaseService.heartbeat(
                new HeartbeatRequest(lease.leaseToken(), 0, Instant.now(clock)));
        assertThat(r3.consumedSecondsTotal()).isGreaterThanOrEqualTo(consumedAfterFirst);

        SessionLease fromDb = testSupport.getLeaseByToken(lease.leaseToken());
        ViewingPlan plan = testSupport.getPlan(lease.planId());
        assertThat(fromDb.getConsumedSecondsTotal()).isEqualTo(plan.getConsumedSeconds());
    }

    @Test
    void longElapsedTimeDoesNotExceedQuotaWhenSettled() {
        Member child = fixtures.createChild("UTC");
        LeaseResponse lease = leaseService.acquire(
                new LeaseRequest(child.getId(), "c1", null));
        assertThat(lease.sessionGrantedSeconds()).isEqualTo(QuotaPolicyService.CHILD_SESSION_SECONDS);

        testSupport.setLastHeartbeatBack(lease.leaseToken(), 600);

        Throwable t = catchThrowable(() ->
                leaseService.heartbeat(new HeartbeatRequest(lease.leaseToken(), 0, Instant.now(clock))));
        assertThat(t).isInstanceOf(LeaseException.class);

        ViewingPlan after = testSupport.getPlan(lease.planId());
        SessionLease afterLease = testSupport.getLeaseByToken(lease.leaseToken());
        assertThat(after.getConsumedSeconds())
                .isLessThanOrEqualTo(after.getDailyLimitSeconds() + after.getExtensionSeconds());
        assertThat(afterLease.getConsumedSecondsTotal())
                .isLessThanOrEqualTo(QuotaPolicyService.CHILD_SESSION_SECONDS);
    }
}
