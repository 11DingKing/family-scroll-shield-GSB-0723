package com.family.scrollshield;

import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.dto.HeartbeatResponse;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.ViewingPlanRepository;
import com.family.scrollshield.service.LeaseService;
import com.family.scrollshield.service.QuotaPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL + Redis coverage for heartbeats that span the member-local midnight
 * boundary. Verifies split settlement (pre-midnight to the old day, post-midnight to the
 * new day), that a full old day never over-bills while after-midnight time is still
 * counted, that the single-session cap holds across long cross-midnight gaps, that a new
 * lease can be acquired for the new day, and that a DST-adjacent midnight behaves correctly.
 */
@SpringBootTest
@Import({TestFixtureFactory.class, LeaseTestSupport.class, TestClockConfig.class})
class CrossMidnightSettlementTest extends AbstractIntegrationTest {

    @Autowired
    LeaseService leaseService;
    @Autowired
    SessionLeaseRepository leaseRepository;
    @Autowired
    ViewingPlanRepository planRepository;
    @Autowired
    TestFixtureFactory fixtures;
    @Autowired
    LeaseTestSupport support;

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @BeforeEach
    void reset() {
        support.flushRedis();
    }

    private void setLocal(int year, int month, int day, int hour, int minute) {
        CLOCK.setInstant(ZonedDateTime.of(year, month, day, hour, minute, 0, 0, NY).toInstant());
    }

    @Test
    void heartbeatSplitsConsumptionAcrossMidnight() {
        // Grant at 23:50 local Jan 15 (EST). Adult single session = 15 min.
        setLocal(2026, 1, 15, 23, 50);
        Member adult = fixtures.adult("America/New_York");
        SessionLease lease = leaseService.acquire(adult.getExternalId());

        // Heartbeat 15 minutes later at 00:05 local Jan 16: 10 min belong to the 15th, 5 to the 16th.
        setLocal(2026, 1, 16, 0, 5);
        HeartbeatResponse response = leaseService.heartbeat(lease.getLeaseToken(), null);

        assertThat(response.status()).isEqualTo(LeaseStatus.CROSSED_MIDNIGHT);
        assertThat(response.quotaExhausted()).isTrue();

        ViewingPlan oldPlan = planRepository.findByMemberIdAndPlanDate(adult.getId(), LocalDate.of(2026, 1, 15))
                .orElseThrow();
        ViewingPlan newPlan = planRepository.findByMemberIdAndPlanDate(adult.getId(), LocalDate.of(2026, 1, 16))
                .orElseThrow();

        // 23:50 -> 00:00 = 600s billed to the old day; 00:00 -> 00:05 = 300s to the new day.
        assertThat(oldPlan.getConsumedSeconds()).isEqualTo(600);
        assertThat(newPlan.getConsumedSeconds()).isEqualTo(300);
        // Total equals the whole elapsed span and does not exceed the session grant.
        assertThat(oldPlan.getConsumedSeconds() + newPlan.getConsumedSeconds()).isEqualTo(900);

        SessionLease closed = leaseRepository.findById(lease.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(LeaseStatus.CROSSED_MIDNIGHT);
        assertThat(closed.getConsumedSecondsTotal()).isEqualTo(900);
    }

    @Test
    void oldDayFullDoesNotOverBillButAfterMidnightStillCounts() {
        setLocal(2026, 1, 15, 23, 55);
        Member adult = fixtures.adult("America/New_York");
        SessionLease lease = leaseService.acquire(adult.getExternalId());

        // Simulate the old day already being fully consumed (60 min) by some earlier sessions.
        ViewingPlan oldPlan = planRepository.findById(lease.getPlanId()).orElseThrow();
        oldPlan.setConsumedSeconds(QuotaPolicyService.ADULT_DAILY_SECONDS);
        planRepository.saveAndFlush(oldPlan);

        // Cross into 00:03 local Jan 16: pre-midnight window has no budget, post-midnight has 3 min.
        setLocal(2026, 1, 16, 0, 3);
        HeartbeatResponse response = leaseService.heartbeat(lease.getLeaseToken(), null);
        assertThat(response.status()).isEqualTo(LeaseStatus.CROSSED_MIDNIGHT);

        ViewingPlan reloadedOld = planRepository.findByMemberIdAndPlanDate(adult.getId(), LocalDate.of(2026, 1, 15))
                .orElseThrow();
        ViewingPlan newPlan = planRepository.findByMemberIdAndPlanDate(adult.getId(), LocalDate.of(2026, 1, 16))
                .orElseThrow();

        // Old day was full: pre-midnight billed zero (never exceeds the daily budget).
        assertThat(reloadedOld.getConsumedSeconds()).isEqualTo(QuotaPolicyService.ADULT_DAILY_SECONDS);
        // After-midnight viewing (00:00 -> 00:03 = 180s) is still counted against the new day.
        assertThat(newPlan.getConsumedSeconds()).isEqualTo(180);
    }

    @Test
    void longCrossMidnightHeartbeatStillBoundedBySessionCap() {
        setLocal(2026, 1, 15, 23, 50);
        Member adult = fixtures.adult("America/New_York");
        SessionLease lease = leaseService.acquire(adult.getExternalId());

        // A very long gap (many hours) crossing midnight. Total billed must not exceed the
        // 15-minute single-session grant, split at midnight.
        setLocal(2026, 1, 16, 4, 0);
        HeartbeatResponse response = leaseService.heartbeat(lease.getLeaseToken(), null);
        assertThat(response.status()).isEqualTo(LeaseStatus.CROSSED_MIDNIGHT);

        ViewingPlan oldPlan = planRepository.findByMemberIdAndPlanDate(adult.getId(), LocalDate.of(2026, 1, 15))
                .orElseThrow();
        ViewingPlan newPlan = planRepository.findByMemberIdAndPlanDate(adult.getId(), LocalDate.of(2026, 1, 16))
                .orElseThrow();

        // 23:50 -> 00:00 = 600s to the old day; the session cap leaves only 300s for the new day.
        assertThat(oldPlan.getConsumedSeconds()).isEqualTo(600);
        assertThat(newPlan.getConsumedSeconds()).isEqualTo(300);
        assertThat(oldPlan.getConsumedSeconds() + newPlan.getConsumedSeconds())
                .isEqualTo(QuotaPolicyService.ADULT_SESSION_SECONDS);
    }

    @Test
    void reacquireAfterMidnightGetsFreshLeaseAndDay() {
        setLocal(2026, 1, 15, 23, 55);
        Member adult = fixtures.adult("America/New_York");
        SessionLease first = leaseService.acquire(adult.getExternalId());

        setLocal(2026, 1, 16, 0, 2);
        leaseService.heartbeat(first.getLeaseToken(), null); // closes as CROSSED_MIDNIGHT

        // Losing Redis at the boundary must not affect correctness.
        support.flushRedis();

        SessionLease second = leaseService.acquire(adult.getExternalId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        // The fresh lease is bound to the new member-local day's plan.
        ViewingPlan newPlan = planRepository.findByMemberIdAndPlanDate(adult.getId(), LocalDate.of(2026, 1, 16))
                .orElseThrow();
        assertThat(second.getPlanId()).isEqualTo(newPlan.getId());
        // Exactly one active lease exists.
        assertThat(leaseRepository.findByMemberIdAndStatus(adult.getId(), LeaseStatus.ACTIVE)
                .map(SessionLease::getId)).contains(second.getId());
    }

    @Test
    void crossMidnightOnDstAdjacentNightSplitsOnRealInstants() {
        // Night before US spring-forward (2026-03-08 02:00 -> 03:00). Midnight 03-07 -> 03-08
        // is a normal boundary; the split must still be computed on real instants.
        setLocal(2026, 3, 7, 23, 55);
        Member adult = fixtures.adult("America/New_York");
        SessionLease lease = leaseService.acquire(adult.getExternalId());

        setLocal(2026, 3, 8, 0, 10);
        HeartbeatResponse response = leaseService.heartbeat(lease.getLeaseToken(), null);
        assertThat(response.status()).isEqualTo(LeaseStatus.CROSSED_MIDNIGHT);

        ViewingPlan oldPlan = planRepository.findByMemberIdAndPlanDate(adult.getId(), LocalDate.of(2026, 3, 7))
                .orElseThrow();
        ViewingPlan newPlan = planRepository.findByMemberIdAndPlanDate(adult.getId(), LocalDate.of(2026, 3, 8))
                .orElseThrow();

        // 23:55 -> 00:00 = 300s (old day); 00:00 -> 00:10 = 600s (new day).
        assertThat(oldPlan.getConsumedSeconds()).isEqualTo(300);
        assertThat(newPlan.getConsumedSeconds()).isEqualTo(600);
    }
}
