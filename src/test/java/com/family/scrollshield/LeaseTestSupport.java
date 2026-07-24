package com.family.scrollshield;

import com.family.scrollshield.repository.ExtensionApprovalRepository;
import com.family.scrollshield.repository.LeaseHeartbeatRepository;
import com.family.scrollshield.repository.OutboxEventRepository;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.ViewingPlanRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared test helpers: cleaning tables between tests and simulating a total Redis loss.
 */
@Component
public class LeaseTestSupport {

    private final SessionLeaseRepository leaseRepository;
    private final ViewingPlanRepository planRepository;
    private final ExtensionApprovalRepository approvalRepository;
    private final LeaseHeartbeatRepository heartbeatRepository;
    private final OutboxEventRepository outboxRepository;
    private final StringRedisTemplate redis;

    public LeaseTestSupport(SessionLeaseRepository leaseRepository,
                            ViewingPlanRepository planRepository,
                            ExtensionApprovalRepository approvalRepository,
                            LeaseHeartbeatRepository heartbeatRepository,
                            OutboxEventRepository outboxRepository,
                            StringRedisTemplate redis) {
        this.leaseRepository = leaseRepository;
        this.planRepository = planRepository;
        this.approvalRepository = approvalRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.outboxRepository = outboxRepository;
        this.redis = redis;
    }

    /** Simulate a complete Redis outage / eviction: the accelerator cache is gone. */
    public void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    public long countActiveLeaseKeys() {
        var keys = redis.keys("lease:active:member:*");
        return keys == null ? 0 : keys.size();
    }
}
