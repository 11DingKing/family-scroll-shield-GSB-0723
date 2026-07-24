package com.family.scrollshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.dto.HeartbeatRequest;
import com.family.scrollshield.dto.LeaseRequest;
import com.family.scrollshield.dto.LeaseResponse;
import com.family.scrollshield.service.LeaseException;
import com.family.scrollshield.service.LeaseRedisCache;
import com.family.scrollshield.service.LeaseService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CacheLossRecoveryTest extends AbstractIntegrationTest {

    @Autowired
    private LeaseService leaseService;

    @Autowired
    private TestFixtureFactory fixtures;

    @Autowired
    private LeaseRedisCache redisCache;

    @Autowired
    private LeaseTestSupport testSupport;

    @Test
    void heartbeatWorksAfterRedisLossUsingPostgresAuthority() throws Exception {
        Member adult = fixtures.createAdult("UTC");
        LeaseResponse lease = leaseService.acquire(
                new LeaseRequest(adult.getId(), "c1", null));

        redisCache.clearAll();
        assertThat(redisCache.currentActiveToken(adult.getId())).isNull();

        Thread.sleep(2000);

        HeartbeatRequest hb = new HeartbeatRequest(lease.leaseToken(), 0, Instant.now());
        var resp = leaseService.heartbeat(hb);
        assertThat(resp.mustStop()).isFalse();
        assertThat(resp.consumedSecondsTotal()).isGreaterThanOrEqualTo(0);

        SessionLease fromDb = testSupport.getLeaseByToken(lease.leaseToken());
        assertThat(fromDb.getStatus()).isEqualTo(com.family.scrollshield.domain.LeaseStatus.ACTIVE);
    }

    @Test
    void releaseWorksAfterRedisLoss() {
        Member adult = fixtures.createAdult("UTC");
        LeaseResponse lease = leaseService.acquire(
                new LeaseRequest(adult.getId(), "c1", null));

        redisCache.clearAll();

        leaseService.release(lease.leaseToken(), "client_release");

        SessionLease fromDb = testSupport.getLeaseByToken(lease.leaseToken());
        assertThat(fromDb.getStatus()).isEqualTo(com.family.scrollshield.domain.LeaseStatus.RELEASED);

        LeaseResponse second = leaseService.acquire(
                new LeaseRequest(adult.getId(), "c2", null));
        assertThat(second.leaseToken()).isNotEqualTo(lease.leaseToken());
    }

    @Test
    void cacheLossDoesNotPermitDoubleDailyConsumption() throws Exception {
        Member child = fixtures.createChild("UTC");
        LeaseResponse lease = leaseService.acquire(
                new LeaseRequest(child.getId(), "c1", null));

        redisCache.clearAll();

        testSupport.setLastHeartbeatBack(lease.leaseToken(), 600);

        Throwable t = catchThrowable(() ->
                leaseService.heartbeat(new HeartbeatRequest(lease.leaseToken(), 0, Instant.now())));
        assertThat(t).isInstanceOf(LeaseException.class);

        SessionLease fromDb = testSupport.getLeaseByToken(lease.leaseToken());
        assertThat(fromDb.getConsumedSecondsTotal())
                .isLessThanOrEqualTo(com.family.scrollshield.service.QuotaPolicyService.CHILD_SESSION_SECONDS);
    }
}
