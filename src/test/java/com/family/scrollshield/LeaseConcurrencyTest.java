package com.family.scrollshield;

import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.service.LeaseException;
import com.family.scrollshield.service.LeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the single-active-lease invariant under real concurrency, expired-lease takeover,
 * and — crucially — that state recovers correctly from PostgreSQL after Redis is lost.
 */
@SpringBootTest
@Import({TestFixtureFactory.class, LeaseTestSupport.class, TestClockConfig.class})
class LeaseConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    LeaseService leaseService;
    @Autowired
    SessionLeaseRepository leaseRepository;
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
    void concurrentAcquireOnlyOneSucceeds() throws Exception {
        Member member = fixtures.adult("UTC");
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            AtomicInteger success = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();

            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    try {
                        leaseService.acquire(member.getExternalId());
                        success.incrementAndGet();
                    } catch (LeaseException ex) {
                        if (ex.getCode() == LeaseException.Code.ACTIVE_LEASE_EXISTS) {
                            conflicts.incrementAndGet();
                        } else {
                            throw ex;
                        }
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }

            assertThat(success.get()).isEqualTo(1);
            assertThat(conflicts.get()).isEqualTo(threads - 1);
            assertThat(leaseRepository.findByMemberIdAndStatus(member.getId(), LeaseStatus.ACTIVE))
                    .isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredLeaseAllowsTakeover() {
        Member member = fixtures.adult("UTC");
        SessionLease first = leaseService.acquire(member.getExternalId());

        // Advance well past the lease expiry so the slot is reclaimable.
        CLOCK.advanceSeconds(10_000);

        SessionLease second = leaseService.acquire(member.getExternalId());
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(LeaseStatus.ACTIVE);

        // Exactly one ACTIVE lease exists for the member.
        assertThat(leaseRepository.findByMemberIdAndStatus(member.getId(), LeaseStatus.ACTIVE)
                .map(SessionLease::getId)).contains(second.getId());
    }

    @Test
    void cacheLoss_recoversStateFromPostgres() {
        Member member = fixtures.adult("UTC");
        SessionLease lease = leaseService.acquire(member.getExternalId());

        // Nuke the Redis accelerator entirely.
        support.flushRedis();
        assertThat(support.countActiveLeaseKeys()).isZero();

        // A second acquire must STILL see the active lease (recovered from Postgres) and be
        // rejected, proving Redis loss does not permit a duplicate active lease.
        assertThat(catchCode(() -> leaseService.acquire(member.getExternalId())))
                .isEqualTo(LeaseException.Code.ACTIVE_LEASE_EXISTS);

        // Heartbeat still works using Postgres authority and re-warms the cache.
        CLOCK.advanceSeconds(60);
        leaseService.heartbeat(lease.getLeaseToken(), null);
        assertThat(support.countActiveLeaseKeys()).isEqualTo(1);
    }

    private LeaseException.Code catchCode(Runnable r) {
        try {
            r.run();
            return null;
        } catch (LeaseException ex) {
            return ex.getCode();
        }
    }
}
