package com.family.scrollshield.service;

import com.family.scrollshield.domain.LeaseHeartbeat;
import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.dto.HeartbeatResponse;
import com.family.scrollshield.repository.LeaseHeartbeatRepository;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.ViewingPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Core coordinator for session leases. Correctness invariants:
 *
 * <ul>
 *   <li><b>Single active lease per member</b> — enforced by the partial unique index
 *       {@code idx_one_active_lease_per_member}; concurrent acquires race on the
 *       database and exactly one wins.</li>
 *   <li><b>PostgreSQL is authoritative</b> — consumed time is settled into the locked
 *       {@link ViewingPlan} row inside a transaction. Redis is only a hint and may be
 *       lost at any time without affecting correctness.</li>
 *   <li><b>Idempotent, monotonic billing</b> — time is billed from server-observed
 *       elapsed wall clock between heartbeats and clamped to session/daily budgets, so
 *       client retries, duplicate or out-of-order heartbeats, and long stalls can never
 *       double-charge or exceed quota.</li>
 *   <li><b>Auditable</b> — every state change is written to the transactional outbox and
 *       every accepted heartbeat is appended to {@code lease_heartbeats}.</li>
 * </ul>
 */
@Service
public class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    private final SessionLeaseRepository leaseRepository;
    private final ViewingPlanRepository planRepository;
    private final LeaseHeartbeatRepository heartbeatRepository;
    private final ViewingPlanService planService;
    private final MemberService memberService;
    private final QuotaPolicyService quotaPolicy;
    private final LeaseRedisCache cache;
    private final OutboxService outbox;

    private final String nodeId;
    private final int expiryMarginSeconds;

    public LeaseService(SessionLeaseRepository leaseRepository,
                        ViewingPlanRepository planRepository,
                        LeaseHeartbeatRepository heartbeatRepository,
                        ViewingPlanService planService,
                        MemberService memberService,
                        QuotaPolicyService quotaPolicy,
                        LeaseRedisCache cache,
                        OutboxService outbox,
                        @Value("${scrollshield.node-id:node-1}") String nodeId,
                        @Value("${scrollshield.lease.expiry-margin-seconds:90}") int expiryMarginSeconds) {
        this.leaseRepository = leaseRepository;
        this.planRepository = planRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.planService = planService;
        this.memberService = memberService;
        this.quotaPolicy = quotaPolicy;
        this.cache = cache;
        this.outbox = outbox;
        this.nodeId = nodeId;
        this.expiryMarginSeconds = expiryMarginSeconds;
    }

    // ---- outbox payloads ------------------------------------------------------------

    public record PlanOpenedPayload(UUID planId, UUID memberId, LocalDate planDate, int dailyLimitSeconds) {
    }

    public record LeaseGrantedPayload(UUID leaseId, UUID memberId, UUID planId, String nodeId,
                                      int sessionGrantedSeconds, OffsetDateTime grantedAt,
                                      OffsetDateTime expiresAt) {
    }

    public record HeartbeatPayload(UUID leaseId, UUID memberId, UUID planId, int incrementSeconds,
                                   int leaseConsumedTotal, int planConsumedSeconds,
                                   OffsetDateTime observedAt, OffsetDateTime nextExpiresAt) {
    }

    public record LeaseTerminatedPayload(UUID leaseId, UUID memberId, UUID planId, LeaseStatus status,
                                         int leaseConsumedTotal, int planConsumedSeconds,
                                         OffsetDateTime terminatedAt) {
    }

    // ---- acquire --------------------------------------------------------------------

    /**
     * Acquire the single active lease for a member. Enforces one-active-lease, daily and
     * single-session quota, and the bedtime blackout. Expired leases are reaped and taken
     * over. Concurrent acquires race on the unique index; losers get ACTIVE_LEASE_EXISTS.
     */
    @Transactional
    public SessionLease acquire(String memberExternalId) {
        Member member = memberService.requireByExternalId(memberExternalId);
        Instant now = quotaPolicy.now();
        LocalDate planDate = quotaPolicy.memberLocalDate(member, now);
        ViewingPlan plan = planService.getOrCreatePlan(member, planDate);

        // Reap / take over any pre-existing active lease (row-locked to serialize takeover).
        Optional<SessionLease> existing = leaseRepository.lockActiveByMember(member.getId());
        if (existing.isPresent()) {
            SessionLease active = existing.get();
            if (active.getExpiresAt().toInstant().isAfter(now)) {
                throw new LeaseException(LeaseException.Code.ACTIVE_LEASE_EXISTS,
                        "member already holds an active lease");
            }
            // Expired but never reaped: settle final time and free the slot before re-granting.
            settleAndClose(member, active, plan, now, LeaseStatus.EXPIRED);
            // Flush the EXPIRED transition before inserting the new lease, otherwise
            // Hibernate orders the INSERT ahead of the UPDATE and the partial unique index
            // (one ACTIVE lease per member) would reject the fresh row.
            leaseRepository.flush();
        }

        // Bedtime blackout blocks new sessions outright.
        if (quotaPolicy.inBedtimeBlackout(member, now)) {
            throw new LeaseException(LeaseException.Code.BEDTIME_BLACKOUT,
                    "within bedtime blackout window; no new sessions permitted");
        }

        int remaining = plan.remainingSeconds();
        if (remaining <= 0) {
            throw new LeaseException(LeaseException.Code.QUOTA_EXHAUSTED,
                    "daily quota exhausted");
        }

        int sessionLimit = quotaPolicy.singleSessionSeconds(member.getAgeGroup());
        int beforeBedtime = quotaPolicy.secondsAvailableBeforeBedtime(member, now, sessionLimit);
        int grant = Math.min(Math.min(sessionLimit, remaining), beforeBedtime);
        if (grant <= 0) {
            throw new LeaseException(LeaseException.Code.BEDTIME_BLACKOUT,
                    "no time available before the bedtime window");
        }

        OffsetDateTime expiresAt = nextExpiry(member, now, grant);

        SessionLease lease = SessionLease.builder()
                .id(UUID.randomUUID())
                .memberId(member.getId())
                .planId(plan.getId())
                .leaseToken(UUID.randomUUID().toString())
                .nodeId(nodeId)
                .status(LeaseStatus.ACTIVE)
                .grantedAt(now.atOffset(ZoneOffset.UTC))
                .expiresAt(expiresAt)
                .lastHeartbeatAt(now.atOffset(ZoneOffset.UTC))
                .sessionGrantedSeconds(grant)
                .consumedSecondsTotal(0)
                .lastIncrementSeconds(0)
                .revision(0)
                .build();
        try {
            lease = leaseRepository.saveAndFlush(lease);
        } catch (DataIntegrityViolationException race) {
            // Lost the race for the single active slot.
            throw new LeaseException(LeaseException.Code.ACTIVE_LEASE_EXISTS,
                    "member already holds an active lease");
        }

        outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_GRANTED,
                new LeaseGrantedPayload(lease.getId(), member.getId(), plan.getId(), nodeId,
                        grant, lease.getGrantedAt(), lease.getExpiresAt()));
        cache.putActive(lease);
        return lease;
    }

    // ---- heartbeat ------------------------------------------------------------------

    /**
     * Process a heartbeat: bill server-observed elapsed time to the authoritative plan,
     * clamped to the single-session and daily budgets, and extend the takeover deadline.
     * The lease and plan rows are pessimistically locked so concurrent heartbeats and
     * takeovers serialize; billing is monotonic so duplicates/out-of-order beats are safe.
     */
    @Transactional
    public HeartbeatResponse heartbeat(String leaseToken, OffsetDateTime clientNow) {
        SessionLease lease = leaseRepository.lockByLeaseToken(leaseToken)
                .orElseThrow(() -> new LeaseException(LeaseException.Code.LEASE_NOT_FOUND,
                        "lease not found"));
        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            throw new LeaseException(LeaseException.Code.LEASE_NOT_ACTIVE,
                    "lease is not active: " + lease.getStatus());
        }

        Member member = memberService.requireById(lease.getMemberId());
        Instant now = quotaPolicy.now();

        // Cross-midnight: settle the session against the old day and close it. The client
        // must acquire a fresh lease for the new member-local day.
        if (quotaPolicy.hasCrossedMidnight(member, lease.getGrantedAt().atZoneSameInstant(quotaPolicy.zoneOf(member)).toLocalDate(), now)) {
            ViewingPlan oldPlan = planRepository.lockById(lease.getPlanId())
                    .orElseThrow(() -> new LeaseException(LeaseException.Code.LEASE_NOT_ACTIVE, "plan missing"));
            SettleResult settled = settleAndClose(member, lease, oldPlan, now, LeaseStatus.CROSSED_MIDNIGHT);
            return new HeartbeatResponse(lease.getId(), LeaseStatus.CROSSED_MIDNIGHT,
                    lease.getExpiresAt(), settled.increment(), lease.getConsumedSecondsTotal(),
                    oldPlan.getConsumedSeconds(), oldPlan.remainingSeconds(), true);
        }

        ViewingPlan plan = planRepository.lockById(lease.getPlanId())
                .orElseThrow(() -> new LeaseException(LeaseException.Code.LEASE_NOT_ACTIVE, "plan missing"));

        SettleResult settled = settleElapsed(member, lease, plan, now);

        boolean quotaExhausted = settled.quotaExhausted();
        LeaseStatus resultStatus;
        OffsetDateTime nextExpires;
        if (quotaExhausted) {
            // No budget left: terminate the lease and free the single slot.
            finalizeTermination(member, lease, plan, now, LeaseStatus.EXPIRED);
            resultStatus = LeaseStatus.EXPIRED;
            nextExpires = lease.getExpiresAt();
        } else {
            resultStatus = LeaseStatus.ACTIVE;
            nextExpires = nextExpiry(member, now, lease.getSessionGrantedSeconds() - lease.getConsumedSecondsTotal());
            lease.setExpiresAt(nextExpires);
            lease.setLastHeartbeatAt(now.atOffset(ZoneOffset.UTC));
            lease.setLastIncrementSeconds(settled.increment());
            lease.setRevision(lease.getRevision() + 1);
            leaseRepository.save(lease);
            cache.putActive(lease);
        }

        heartbeatRepository.save(LeaseHeartbeat.builder()
                .leaseId(lease.getId())
                .observedAt(now.atOffset(ZoneOffset.UTC))
                .clientNow(clientNow)
                .incrementSeconds(settled.increment())
                .totalConsumed(lease.getConsumedSecondsTotal())
                .nextExpiresAt(nextExpires)
                .build());

        outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.HEARTBEAT,
                new HeartbeatPayload(lease.getId(), member.getId(), plan.getId(), settled.increment(),
                        lease.getConsumedSecondsTotal(), plan.getConsumedSeconds(),
                        now.atOffset(ZoneOffset.UTC), nextExpires));

        return new HeartbeatResponse(lease.getId(), resultStatus, nextExpires, settled.increment(),
                lease.getConsumedSecondsTotal(), plan.getConsumedSeconds(), plan.remainingSeconds(),
                quotaExhausted);
    }

    // ---- release --------------------------------------------------------------------

    /**
     * Gracefully release a lease. Idempotent: releasing an already-terminated lease is a
     * no-op that returns the current state. Final elapsed time is settled before closing.
     */
    @Transactional
    public SessionLease release(String leaseToken) {
        SessionLease lease = leaseRepository.lockByLeaseToken(leaseToken)
                .orElseThrow(() -> new LeaseException(LeaseException.Code.LEASE_NOT_FOUND,
                        "lease not found"));
        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            return lease; // already released/expired: idempotent success
        }
        Member member = memberService.requireById(lease.getMemberId());
        Instant now = quotaPolicy.now();
        ViewingPlan plan = planRepository.lockById(lease.getPlanId())
                .orElseThrow(() -> new LeaseException(LeaseException.Code.LEASE_NOT_ACTIVE, "plan missing"));
        settleAndClose(member, lease, plan, now, LeaseStatus.RELEASED);
        return lease;
    }

    // ---- shared settlement helpers --------------------------------------------------

    private record SettleResult(int increment, boolean quotaExhausted) {
    }

    /**
     * Bill server-observed elapsed time since the last heartbeat into the plan, clamped to
     * the single-session grant and the remaining daily budget. Monotonic: never decreases
     * consumption, so duplicate or out-of-order heartbeats add zero.
     */
    private SettleResult settleElapsed(Member member, SessionLease lease, ViewingPlan plan, Instant now) {
        long elapsed = Duration.between(lease.getLastHeartbeatAt().toInstant(), now).getSeconds();
        if (elapsed < 0) {
            elapsed = 0; // out-of-order / clock skew: never bill negative time
        }

        // Consumption already attributed to this lease is included in plan.consumedSeconds.
        int otherConsumption = plan.getConsumedSeconds() - lease.getConsumedSecondsTotal();
        int planCeilingForLease = plan.totalBudgetSeconds() - otherConsumption;

        int proposed = (int) Math.min((long) lease.getConsumedSecondsTotal() + elapsed, Integer.MAX_VALUE);
        int newLeaseTotal = Math.min(proposed, lease.getSessionGrantedSeconds());
        newLeaseTotal = Math.min(newLeaseTotal, planCeilingForLease);
        newLeaseTotal = Math.max(newLeaseTotal, lease.getConsumedSecondsTotal()); // monotonic

        int increment = newLeaseTotal - lease.getConsumedSecondsTotal();
        if (increment > 0) {
            lease.setConsumedSecondsTotal(newLeaseTotal);
            plan.setConsumedSeconds(plan.getConsumedSeconds() + increment);
            planRepository.save(plan);
        }

        boolean sessionDone = newLeaseTotal >= lease.getSessionGrantedSeconds();
        boolean dailyDone = plan.remainingSeconds() <= 0;
        return new SettleResult(increment, sessionDone || dailyDone);
    }

    /** Settle final elapsed time and transition the lease to a terminal status. */
    private SettleResult settleAndClose(Member member, SessionLease lease, ViewingPlan plan,
                                        Instant now, LeaseStatus terminalStatus) {
        SettleResult result = settleElapsed(member, lease, plan, now);
        finalizeTermination(member, lease, plan, now, terminalStatus);
        return result;
    }

    private void finalizeTermination(Member member, SessionLease lease, ViewingPlan plan,
                                     Instant now, LeaseStatus terminalStatus) {
        lease.setStatus(terminalStatus);
        lease.setLastHeartbeatAt(now.atOffset(ZoneOffset.UTC));
        lease.setRevision(lease.getRevision() + 1);
        leaseRepository.save(lease);
        cache.evict(member.getId());
        outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_TERMINATED,
                new LeaseTerminatedPayload(lease.getId(), member.getId(), plan.getId(), terminalStatus,
                        lease.getConsumedSecondsTotal(), plan.getConsumedSeconds(),
                        now.atOffset(ZoneOffset.UTC)));
    }

    /**
     * The next takeover deadline: server time plus the expiry margin, but never past the
     * point where the remaining session budget would run out, nor past the bedtime window.
     */
    private OffsetDateTime nextExpiry(Member member, Instant now, int remainingSessionSeconds) {
        int margin = Math.max(1, Math.min(expiryMarginSeconds, Math.max(1, remainingSessionSeconds)));
        OffsetDateTime marginExpiry = now.plusSeconds(margin).atOffset(ZoneOffset.UTC);
        OffsetDateTime capped = quotaPolicy.cappedExpiry(member, now, margin);
        return capped.toInstant().isBefore(marginExpiry.toInstant()) ? capped : marginExpiry;
    }
}
