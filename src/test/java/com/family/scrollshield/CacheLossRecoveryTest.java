package com.family.scrollshield;

import com.family.scrollshield.domain.LeaseStatus;
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
 * The headline guarantee: Redis is a lossable accelerator. These tests flush Redis at the
 * worst moment and assert that heartbeat billing, release, and quota enforcement all
 * recover correct state from PostgreSQL — no double consumption, no over-grant.
 */
@SpringBootTest
@Import({TestFixtureFactory.class, LeaseTestSupport.class, TestClockConfig.class})
class CacheLossRecoveryTest extends AbstractIntegrationTest {

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
    void heartbeatWorksAfterRedisLossUsingPostgresAuthority() {
        Member member = fixtures.adult("UTC");
        SessionLease lease = leaseService.acquire(member.getExternalId());

        CLOCK.advanceSeconds(120);
        support.flushRedis(); // lose the cache right before billing

        HeartbeatResponse response = leaseService.heartbeat(lease.getLeaseToken(), null);
        assertThat(response.status()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(response.incrementSeconds()).isEqualTo(120);

        ViewingPlan plan = planRepository.findById(lease.getPlanId()).orElseThrow();
        assertThat(plan.getConsumedSeconds()).isEqualTo(120);
        // Cache was re-warmed from Postgres authority.
        assertThat(support.countActiveLeaseKeys()).isEqualTo(1);
    }

    @Test
    void releaseWorksAfterRedisLoss() {
        Member member = fixtures.adult("UTC");
        SessionLease lease = leaseService.acquire(member.getExternalId());

        CLOCK.advanceSeconds(90);
        support.flushRedis();

        SessionLease released = leaseService.release(lease.getLeaseToken());
        assertThat(released.getStatus()).isEqualTo(LeaseStatus.RELEASED);

        ViewingPlan plan = planRepository.findById(lease.getPlanId()).orElseThrow();
        assertThat(plan.getConsumedSeconds()).isEqualTo(90);
        // Slot is freed and cache eviction happened.
        assertThat(support.countActiveLeaseKeys()).isZero();

        // A new lease can be acquired after the clean release.
        SessionLease next = leaseService.acquire(member.getExternalId());
        assertThat(next.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
    }

    @Test
    void cacheLossDoesNotPermitDoubleDailyConsumption() {
        Member member = fixtures.adult("UTC");

        // Session 1: consume the full 15-minute single-session grant.
        SessionLease first = leaseService.acquire(member.getExternalId());
        CLOCK.advanceSeconds(QuotaPolicyService.ADULT_SESSION_SECONDS);
        leaseService.heartbeat(first.getLeaseToken(), null); // hits session cap -> terminates

        support.flushRedis(); // lose cache between sessions

        // Sessions 2..N: keep acquiring and burning session grants until the daily cap.
        int guard = 0;
        while (guard++ < 10) {
            support.flushRedis(); // repeatedly lose the accelerator
            try {
                SessionLease lease = leaseService.acquire(member.getExternalId());
                CLOCK.advanceSeconds(QuotaPolicyService.ADULT_SESSION_SECONDS);
                leaseService.heartbeat(lease.getLeaseToken(), null);
            } catch (com.family.scrollshield.service.LeaseException ex) {
                assertThat(ex.getCode())
                        .isEqualTo(com.family.scrollshield.service.LeaseException.Code.QUOTA_EXHAUSTED);
                break;
            }
        }

        // Despite repeated cache loss, total consumption is capped at the daily 60 minutes.
        ViewingPlan plan = planRepository.findByMemberIdAndPlanDate(
                member.getId(), CLOCK.instant().atZone(java.time.ZoneId.of("UTC")).toLocalDate())
                .orElseThrow();
        assertThat(plan.getConsumedSeconds()).isEqualTo(QuotaPolicyService.ADULT_DAILY_SECONDS);
        assertThat(plan.getConsumedSeconds()).isLessThanOrEqualTo(plan.totalBudgetSeconds());
    }
}
