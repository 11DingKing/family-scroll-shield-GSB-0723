package com.family.scrollshield;

import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.dto.HeartbeatResponse;
import com.family.scrollshield.repository.ViewingPlanRepository;
import com.family.scrollshield.service.LeaseService;
import com.family.scrollshield.service.QuotaPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Heartbeat billing must be monotonic and quota-bounded: retries/duplicates add nothing,
 * and a very long gap between heartbeats (e.g. after a stall) can never bill past the
 * single-session grant or the daily quota.
 */
@SpringBootTest
@Import({TestFixtureFactory.class, LeaseTestSupport.class, TestClockConfig.class})
class HeartbeatIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    LeaseService leaseService;
    @Autowired
    ViewingPlanRepository planRepository;
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
    void duplicateHeartbeatDoesNotDoubleConsume() {
        Member member = fixtures.adult("UTC");
        SessionLease lease = leaseService.acquire(member.getExternalId());

        // Advance 60s, then send the same heartbeat instant twice.
        CLOCK.advanceSeconds(60);
        HeartbeatResponse first = leaseService.heartbeat(lease.getLeaseToken(), null);
        // No time passes before the duplicate.
        HeartbeatResponse duplicate = leaseService.heartbeat(lease.getLeaseToken(), null);

        assertThat(first.incrementSeconds()).isEqualTo(60);
        assertThat(duplicate.incrementSeconds()).isZero();

        ViewingPlan plan = planRepository.findById(lease.getPlanId()).orElseThrow();
        assertThat(plan.getConsumedSeconds()).isEqualTo(60);
    }

    @Test
    void longElapsedTimeDoesNotExceedQuotaWhenSettled() {
        Member member = fixtures.adult("UTC");
        SessionLease lease = leaseService.acquire(member.getExternalId());

        // Simulate a very long stall (2 hours) far exceeding both session and daily limits.
        CLOCK.advanceSeconds(2 * 60 * 60);
        HeartbeatResponse response = leaseService.heartbeat(lease.getLeaseToken(), null);

        // Billed time is clamped to the adult single-session limit (15 min).
        assertThat(response.consumedSecondsTotal())
                .isEqualTo(QuotaPolicyService.ADULT_SESSION_SECONDS);
        assertThat(response.quotaExhausted()).isTrue();

        ViewingPlan plan = planRepository.findById(lease.getPlanId()).orElseThrow();
        // Plan consumption never exceeds the daily budget.
        assertThat(plan.getConsumedSeconds()).isLessThanOrEqualTo(plan.totalBudgetSeconds());
        assertThat(plan.getConsumedSeconds()).isEqualTo(QuotaPolicyService.ADULT_SESSION_SECONDS);
    }
}
