package com.family.scrollshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.dto.LeaseRequest;
import com.family.scrollshield.dto.LeaseResponse;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.service.LeaseException;
import com.family.scrollshield.service.LeaseRedisCache;
import com.family.scrollshield.service.LeaseService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LeaseConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private LeaseService leaseService;

    @Autowired
    private SessionLeaseRepository leaseRepository;

    @Autowired
    private TestFixtureFactory fixtures;

    @Autowired
    private LeaseRedisCache redisCache;

    @Autowired
    private LeaseTestSupport testSupport;

    @Test
    void concurrentAcquireOnlyOneSucceeds() throws Exception {
        Member adult = fixtures.createAdult("Asia/Shanghai");
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    LeaseResponse r = leaseService.acquire(
                            new LeaseRequest(adult.getId(), "client-" + Thread.currentThread().getId(), null));
                    success.incrementAndGet();
                    assertThat(r.leaseToken()).isNotBlank();
                } catch (LeaseException e) {
                    if ("ACTIVE_LEASE_EXISTS".equals(e.getCode())) {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    // ignore lock conflict
                } finally {
                    done.countDown();
                }
            }));
        }

        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        long active = testSupport.countActive(adult.getId());
        assertThat(active).isEqualTo(1);
        assertThat(success.get()).isEqualTo(1);
        assertThat(success.get() + rejected.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void cacheLoss_recoversStateFromPostgres() {
        Member adult = fixtures.createAdult("UTC");

        LeaseResponse lease = leaseService.acquire(
                new LeaseRequest(adult.getId(), "client-1", null));
        assertThat(lease.leaseToken()).isNotBlank();

        redisCache.clearAll();

        String cached = redisCache.currentActiveToken(adult.getId());
        assertThat(cached).isNull();

        Throwable second = catchThrowable(() -> leaseService.acquire(
                new LeaseRequest(adult.getId(), "client-2", null)));
        assertThat(second).isInstanceOf(LeaseException.class);
        assertThat(((LeaseException) second).getCode()).isEqualTo("ACTIVE_LEASE_EXISTS");

        SessionLease fromDb = testSupport.getLeaseByToken(lease.leaseToken());
        assertThat(fromDb.getLeaseToken()).isEqualTo(lease.leaseToken());
        assertThat(fromDb.getStatus()).isEqualTo(com.family.scrollshield.domain.LeaseStatus.ACTIVE);

        redisCache.release(adult.getId(), lease.leaseToken());
    }

    @Test
    void expiredLeaseAllowsTakeover() {
        Member adult = fixtures.createAdult("UTC");
        LeaseResponse first = leaseService.acquire(
                new LeaseRequest(adult.getId(), "client-1", null));

        testSupport.setLastHeartbeatBack(first.leaseToken(), 600);

        LeaseResponse second = leaseService.acquire(
                new LeaseRequest(adult.getId(), "client-2", null));

        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());

        SessionLease old = testSupport.getLeaseByToken(first.leaseToken());
        assertThat(old.getStatus()).isEqualTo(com.family.scrollshield.domain.LeaseStatus.EXPIRED);
    }
}
