package com.family.scrollshield;

import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.ViewingPlanRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LeaseTestSupport {

    private final SessionLeaseRepository leaseRepository;
    private final ViewingPlanRepository planRepository;

    public LeaseTestSupport(SessionLeaseRepository leaseRepository, ViewingPlanRepository planRepository) {
        this.leaseRepository = leaseRepository;
        this.planRepository = planRepository;
    }

    @Transactional
    public void setLastHeartbeatBack(String token, long secondsAgo) {
        SessionLease lease = leaseRepository.lockByToken(token).orElseThrow();
        Instant past = Instant.now().minusSeconds(secondsAgo);
        lease.setLastHeartbeatAt(past);
        lease.setGrantedAt(past);
        leaseRepository.saveAndFlush(lease);
    }

    @Transactional
    public SessionLease getLeaseByToken(String token) {
        return leaseRepository.lockByToken(token).orElseThrow();
    }

    @Transactional
    public ViewingPlan getPlan(UUID planId) {
        return planRepository.lockById(planId).orElseThrow();
    }

    @Transactional
    public long countActive(UUID memberId) {
        return leaseRepository.findByMemberAndStatus(memberId, LeaseStatus.ACTIVE).stream().count();
    }
}
