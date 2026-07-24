package com.family.scrollshield.scheduler;

import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.ViewingPlanRepository;
import com.family.scrollshield.service.AggregateTypes;
import com.family.scrollshield.service.EventTypes;
import com.family.scrollshield.service.LeaseRedisCache;
import com.family.scrollshield.service.MemberService;
import com.family.scrollshield.service.OutboxService;
import com.family.scrollshield.service.QuotaPolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Background reaper that reclaims the single-active-lease slot when a client crashes,
 * a network partition silences heartbeats, or a session crosses the member-local
 * midnight boundary. Each lease is settled and closed in its own transaction, billing
 * the elapsed-but-unsettled server time (clamped to session/daily budgets) so a crash
 * never leaks quota or leaves a stuck slot. This is a safety net; the interactive
 * heartbeat/release paths already settle time on the fly.
 */
@Component
public class LeaseMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeaseMaintenanceScheduler.class);

    private final SessionLeaseRepository leaseRepository;
    private final ViewingPlanRepository planRepository;
    private final MemberService memberService;
    private final QuotaPolicyService quotaPolicy;
    private final LeaseRedisCache cache;
    private final OutboxService outbox;

    public LeaseMaintenanceScheduler(SessionLeaseRepository leaseRepository,
                                     ViewingPlanRepository planRepository,
                                     MemberService memberService,
                                     QuotaPolicyService quotaPolicy,
                                     LeaseRedisCache cache,
                                     OutboxService outbox) {
        this.leaseRepository = leaseRepository;
        this.planRepository = planRepository;
        this.memberService = memberService;
        this.quotaPolicy = quotaPolicy;
        this.cache = cache;
        this.outbox = outbox;
    }

    public record LeaseExpiredPayload(UUID leaseId, UUID memberId, UUID planId, int leaseConsumedTotal,
                                      int planConsumedSeconds, OffsetDateTime expiredAt) {
    }

    public record PlanClosedPayload(UUID planId, UUID memberId, int consumedSeconds,
                                    OffsetDateTime closedAt) {
    }

    @Scheduled(fixedDelayString = "${scrollshield.lease.reaper-interval-ms:15000}")
    public void reapExpiredLeases() {
        Instant now = quotaPolicy.now();
        List<SessionLease> expired = leaseRepository.findExpiredActive(now.atOffset(ZoneOffset.UTC));
        for (SessionLease candidate : expired) {
            try {
                expireOne(candidate.getId(), now);
            } catch (Exception ex) {
                log.warn("Failed to reap lease {}: {}", candidate.getId(), ex.toString());
            }
        }
    }

    /**
     * Settle and close a single expired lease. Re-locks the lease and plan rows and
     * re-checks the ACTIVE status so it is safe against a concurrent heartbeat/release
     * that may have already reclaimed the slot.
     */
    @Transactional
    public void expireOne(UUID leaseId, Instant now) {
        SessionLease lease = leaseRepository.findById(leaseId).orElse(null);
        if (lease == null || lease.getStatus() != LeaseStatus.ACTIVE) {
            return; // already reclaimed by heartbeat/release
        }
        // Re-lock to serialize with interactive paths.
        lease = leaseRepository.lockByLeaseToken(lease.getLeaseToken()).orElse(null);
        if (lease == null || lease.getStatus() != LeaseStatus.ACTIVE) {
            return;
        }
        if (lease.getExpiresAt().toInstant().isAfter(now)) {
            return; // heartbeat extended it in the meantime
        }

        Member member = memberService.requireById(lease.getMemberId());
        ViewingPlan plan = planRepository.lockById(lease.getPlanId()).orElseThrow();

        // Bill the elapsed time up to expiry (already-expired: bill up to the deadline),
        // clamped to the session grant and remaining daily budget. Monotonic.
        long elapsed = Duration.between(lease.getLastHeartbeatAt().toInstant(),
                lease.getExpiresAt().toInstant()).getSeconds();
        if (elapsed < 0) {
            elapsed = 0;
        }
        int otherConsumption = plan.getConsumedSeconds() - lease.getConsumedSecondsTotal();
        int planCeilingForLease = plan.totalBudgetSeconds() - otherConsumption;
        int newLeaseTotal = (int) Math.min((long) lease.getConsumedSecondsTotal() + elapsed, Integer.MAX_VALUE);
        newLeaseTotal = Math.min(newLeaseTotal, lease.getSessionGrantedSeconds());
        newLeaseTotal = Math.min(newLeaseTotal, planCeilingForLease);
        newLeaseTotal = Math.max(newLeaseTotal, lease.getConsumedSecondsTotal());
        int increment = newLeaseTotal - lease.getConsumedSecondsTotal();
        if (increment > 0) {
            lease.setConsumedSecondsTotal(newLeaseTotal);
            plan.setConsumedSeconds(plan.getConsumedSeconds() + increment);
            planRepository.save(plan);
        }

        boolean crossedMidnight = quotaPolicy.hasCrossedMidnight(member,
                lease.getGrantedAt().atZoneSameInstant(quotaPolicy.zoneOf(member)).toLocalDate(), now);
        LeaseStatus terminal = crossedMidnight ? LeaseStatus.CROSSED_MIDNIGHT : LeaseStatus.EXPIRED;

        lease.setStatus(terminal);
        lease.setRevision(lease.getRevision() + 1);
        leaseRepository.save(lease);
        cache.evict(member.getId());

        outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_EXPIRED,
                new LeaseExpiredPayload(lease.getId(), member.getId(), plan.getId(),
                        lease.getConsumedSecondsTotal(), plan.getConsumedSeconds(),
                        now.atOffset(ZoneOffset.UTC)));
    }
}
