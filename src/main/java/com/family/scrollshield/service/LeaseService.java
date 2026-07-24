package com.family.scrollshield.service;

import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.PlanStatus;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.dto.HeartbeatRequest;
import com.family.scrollshield.dto.HeartbeatResponse;
import com.family.scrollshield.dto.LeaseRequest;
import com.family.scrollshield.dto.LeaseResponse;
import com.family.scrollshield.repository.MemberRepository;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.ViewingPlanRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseService {

    private final MemberRepository memberRepository;
    private final ViewingPlanRepository planRepository;
    private final SessionLeaseRepository leaseRepository;
    private final QuotaPolicyService policy;
    private final OutboxService outbox;
    private final LeaseRedisCache redisCache;
    private final Clock clock;

    @Value("${scrollshield.lease.expiry-margin-seconds:90}")
    private long expiryMarginSeconds;

    @Value("${scrollshield.lease.heartbeat-interval-seconds:30}")
    private long heartbeatIntervalSeconds;

    @Value("${scrollshield.lease.max-clock-skew-seconds:5}")
    private long maxClockSkewSeconds;

    @Value("${scrollshield.node-id:${spring.application.name:node}}")
    private String nodeId;

    public long expiryMarginSeconds() {
        return expiryMarginSeconds;
    }

    public Duration heartbeatInterval() {
        return Duration.ofSeconds(heartbeatIntervalSeconds);
    }

    @Transactional
    public LeaseResponse acquire(LeaseRequest req) {
        Instant now = Instant.now(clock);
        Member member = memberRepository.findById(req.memberId())
                .orElseThrow(LeaseException::memberNotFound);

        LocalDate today = policy.localDateFor(member, now);

        ViewingPlan plan = lockOrCreatePlan(member, today);

        if (plan.getStatus() == PlanStatus.CLOSED) {
            throw LeaseException.planClosed();
        }

        policy.assertCanGrant(member, now, plan.remainingSeconds());

        Optional<SessionLease> existingOpt =
                leaseRepository.findByMemberAndStatus(member.getId(), LeaseStatus.ACTIVE);
        if (existingOpt.isPresent()) {
            SessionLease existing = existingOpt.get();
            if (existing.isActiveAt(now, expiryMarginSeconds)) {
                throw LeaseException.activeLeaseExists();
            }
            settleExpiredLease(existing, now, "heartbeat_timeout");
            leaseRepository.flush();
        }

        int grantSeconds = policy.computeSessionGrantSeconds(member, plan.remainingSeconds(), now);
        if (grantSeconds <= 0) {
            throw LeaseException.dailyQuotaExhausted();
        }

        Instant expiresAt = policy.proposedLeaseExpiry(member, now, grantSeconds);
        grantSeconds = (int) Duration.between(now, expiresAt).getSeconds();
        if (grantSeconds <= 0) {
            throw LeaseException.dailyQuotaExhausted();
        }

        String token = UUID.randomUUID().toString().replace("-", "");

        SessionLease lease = SessionLease.builder()
                .id(UUID.randomUUID())
                .member(member)
                .plan(plan)
                .leaseToken(token)
                .nodeId(nodeId)
                .status(LeaseStatus.ACTIVE)
                .grantedAt(now)
                .expiresAt(expiresAt)
                .lastHeartbeatAt(now)
                .sessionGrantedSeconds(grantSeconds)
                .consumedSecondsTotal(0)
                .lastIncrementSeconds(0)
                .revision(0)
                .build();
        try {
            leaseRepository.saveAndFlush(lease);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent lease insert for member {}", member.getId());
            throw LeaseException.activeLeaseExists();
        }

        outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_GRANTED,
                new LeaseGrantedPayload(lease.getId(), member.getId(), plan.getId(), token,
                        grantSeconds, now, expiresAt, nodeId));

        registerAfterCommit(() -> {
            LeaseCacheEntry entry = new LeaseCacheEntry(
                    lease.getId(), member.getId(), token, expiresAt, lease.getRevision());
            redisCache.tryAcquireFastPath(member.getId(), token, entry);
        });

        return toLeaseResponse(lease, plan, now);
    }

    @Transactional
    public HeartbeatResponse heartbeat(HeartbeatRequest req) {
        Instant now = Instant.now(clock);

        SessionLease lease = leaseRepository.lockByToken(req.leaseToken())
                .orElseThrow(LeaseException::leaseNotFound);

        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            throw LeaseException.leaseNotActive();
        }

        if (req.clientNow() != null) {
            // Idempotency / out-of-order protection: if this heartbeat's clientNow is not strictly
            // after the last processed heartbeat (with clock-skew tolerance), treat as a replay.
            Instant frontier = lease.getLastHeartbeatAt().plus(Duration.ofSeconds(maxClockSkewSeconds));
            if (!req.clientNow().isAfter(frontier)) {
                return toHeartbeatResponse(lease, lease.getStatus() != LeaseStatus.ACTIVE);
            }
        }

        if (!lease.isActiveAt(now, expiryMarginSeconds)) {
            settleExpiredLease(lease, now, "heartbeat_late");
            throw LeaseException.leaseExpired();
        }

        Member member = lease.getMember();
        ViewingPlan plan = planRepository.lockById(lease.getPlan().getId())
                .orElseThrow(() -> new IllegalStateException("Plan missing for lease"));

        LocalDate today = policy.localDateFor(member, now);
        if (!today.equals(plan.getPlanDate())) {
            int finalDelta = settleFinalDelta(lease, now);
            applyConsumption(lease, plan, finalDelta);
            lease.setStatus(LeaseStatus.CROSSED_MIDNIGHT);
            lease.setRevision(lease.getRevision() + 1);
            plan.setStatus(PlanStatus.CLOSED);
            plan.setClosedAt(now);
            outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_CROSSED_MIDNIGHT,
                    new LeaseTerminatedPayload(lease.getId(), member.getId(), plan.getId(),
                            lease.getConsumedSecondsTotal(), now, "crossed_midnight"));
            registerAfterCommit(() -> redisCache.release(member.getId(), lease.getLeaseToken()));
            throw LeaseException.crossedMidnight();
        }

        if (member.getAgeGroup() == com.family.scrollshield.domain.AgeGroup.TEEN
                && policy.isWithinBedtimeBlackout(member, now)) {
            int finalDelta = settleFinalDelta(lease, now);
            applyConsumption(lease, plan, finalDelta);
            lease.setStatus(LeaseStatus.REVOKED);
            lease.setRevision(lease.getRevision() + 1);
            outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_REVOKED,
                    new LeaseTerminatedPayload(lease.getId(), member.getId(), plan.getId(),
                            lease.getConsumedSecondsTotal(), now, "bedtime_window"));
            registerAfterCommit(() -> redisCache.release(member.getId(), lease.getLeaseToken()));
            return toHeartbeatResponse(lease, true);
        }

        long wallDeltaSec = Duration.between(lease.getLastHeartbeatAt(), now).getSeconds();
        if (wallDeltaSec < 0) wallDeltaSec = 0;
        long maxReasonable = heartbeatIntervalSeconds + maxClockSkewSeconds + expiryMarginSeconds;
        int delta = (int) Math.min(wallDeltaSec, maxReasonable);

        int sessionRemaining = lease.getSessionGrantedSeconds() - lease.getConsumedSecondsTotal();
        int dailyRemaining = plan.remainingSeconds();
        int effectiveDelta = Math.min(delta, Math.min(sessionRemaining, dailyRemaining));
        if (effectiveDelta < 0) effectiveDelta = 0;

        boolean mustStop = false;
        if (effectiveDelta > 0) {
            applyConsumption(lease, plan, effectiveDelta);
        } else {
            lease.setLastIncrementSeconds(0);
        }

        Instant nextExpires = policy.proposedLeaseExpiry(
                member, lease.getGrantedAt(), lease.getSessionGrantedSeconds());
        if (nextExpires.isBefore(lease.getExpiresAt())) {
            lease.setExpiresAt(nextExpires);
        }

        lease.setLastHeartbeatAt(now);
        lease.setRevision(lease.getRevision() + 1);

        if (lease.getConsumedSecondsTotal() >= lease.getSessionGrantedSeconds()
                || plan.remainingSeconds() <= 0
                || !lease.getExpiresAt().isAfter(now)) {
            lease.setStatus(LeaseStatus.RELEASED);
            plan.setStatus(PlanStatus.CLOSED);
            plan.setClosedAt(now);
            outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_RELEASED,
                    new LeaseTerminatedPayload(lease.getId(), member.getId(), plan.getId(),
                            lease.getConsumedSecondsTotal(), now, "session_limit"));
            registerAfterCommit(() -> redisCache.release(member.getId(), lease.getLeaseToken()));
            mustStop = true;
        } else {
            outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_HEARTBEAT,
                    new HeartbeatPayload(lease.getId(), member.getId(), plan.getId(),
                            effectiveDelta, lease.getConsumedSecondsTotal(), now,
                            lease.getExpiresAt(), lease.getRevision()));
            registerAfterCommit(() -> redisCache.refresh(
                    lease.getLeaseToken(), member.getId(), lease.getExpiresAt(), lease.getRevision()));
        }

        return toHeartbeatResponse(lease, mustStop);
    }

    @Transactional
    public void release(String leaseToken, String reason) {
        Instant now = Instant.now(clock);
        SessionLease lease = leaseRepository.lockByToken(leaseToken)
                .orElseThrow(LeaseException::leaseNotFound);
        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            return;
        }
        ViewingPlan plan = lease.getPlan();
        int finalDelta = settleFinalDelta(lease, now);
        if (finalDelta > 0) {
            ViewingPlan locked = planRepository.lockByMemberAndDate(
                    lease.getMember().getId(), plan.getPlanDate()).orElse(plan);
            applyConsumption(lease, locked, finalDelta);
        }
        lease.setStatus(LeaseStatus.RELEASED);
        lease.setRevision(lease.getRevision() + 1);
        outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_RELEASED,
                new LeaseTerminatedPayload(lease.getId(), lease.getMember().getId(),
                        plan.getId(), lease.getConsumedSecondsTotal(), now,
                        reason == null ? "client_release" : reason));
        registerAfterCommit(() -> redisCache.release(lease.getMember().getId(), lease.getLeaseToken()));
    }

    private void applyConsumption(SessionLease lease, ViewingPlan plan, int delta) {
        if (delta <= 0) {
            lease.setLastIncrementSeconds(0);
            return;
        }
        lease.setConsumedSecondsTotal(lease.getConsumedSecondsTotal() + delta);
        lease.setLastIncrementSeconds(delta);
        plan.setConsumedSeconds(plan.getConsumedSeconds() + delta);
    }

    private int settleFinalDelta(SessionLease lease, Instant now) {
        long wallDelta = Duration.between(lease.getLastHeartbeatAt(), now).getSeconds();
        if (wallDelta < 0) wallDelta = 0;
        int sessionRemaining = lease.getSessionGrantedSeconds() - lease.getConsumedSecondsTotal();
        if (sessionRemaining < 0) sessionRemaining = 0;
        return (int) Math.min(wallDelta, sessionRemaining);
    }

    void settleExpiredLease(SessionLease lease, Instant now, String reason) {
        ViewingPlan plan = planRepository.lockByMemberAndDate(
                lease.getMember().getId(), lease.getPlan().getPlanDate())
                .orElse(lease.getPlan());
        int finalDelta = settleFinalDelta(lease, now);
        if (finalDelta > 0) {
            applyConsumption(lease, plan, finalDelta);
        }
        lease.setStatus(LeaseStatus.EXPIRED);
        lease.setRevision(lease.getRevision() + 1);
        outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_EXPIRED,
                new LeaseTerminatedPayload(lease.getId(), lease.getMember().getId(),
                        lease.getPlan().getId(), lease.getConsumedSecondsTotal(), now, reason));
        registerAfterCommit(() -> redisCache.release(lease.getMember().getId(), lease.getLeaseToken()));
    }

    private ViewingPlan lockOrCreatePlan(Member member, LocalDate date) {
        Optional<ViewingPlan> existing = planRepository.lockByMemberAndDate(member.getId(), date);
        if (existing.isPresent()) {
            return existing.get();
        }
        ViewingPlan fresh = ViewingPlan.builder()
                .id(UUID.randomUUID())
                .member(member)
                .planDate(date)
                .dailyLimitSeconds(policy.dailyLimitSeconds(member.getAgeGroup()))
                .consumedSeconds(0)
                .extensionUsed(false)
                .extensionSeconds(0)
                .status(PlanStatus.OPEN)
                .version(0)
                .build();
        try {
            planRepository.saveAndFlush(fresh);
            outbox.record(AggregateTypes.PLAN, fresh.getId(), EventTypes.PLAN_OPENED,
                    new PlanOpenedPayload(fresh.getId(), member.getId(), date,
                            fresh.getDailyLimitSeconds(), Instant.now(clock)));
            return fresh;
        } catch (DataIntegrityViolationException e) {
            return planRepository.lockByMemberAndDate(member.getId(), date)
                    .orElseThrow(() -> new IllegalStateException("Plan disappeared after conflict"));
        }
    }

    private void registerAfterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        r.run();
                    } catch (Exception e) {
                        log.warn("After-commit cache action failed: {}", e.getMessage());
                    }
                }
            });
        } else {
            r.run();
        }
    }

    LeaseResponse toLeaseResponse(SessionLease lease, ViewingPlan plan, Instant now) {
        Instant nextHb = lease.getLastHeartbeatAt().plusSeconds(heartbeatIntervalSeconds);
        return new LeaseResponse(
                lease.getId(),
                lease.getLeaseToken(),
                lease.getMember().getId(),
                plan.getId(),
                lease.getStatus().name(),
                lease.getGrantedAt(),
                lease.getExpiresAt(),
                nextHb,
                lease.getSessionGrantedSeconds(),
                lease.getConsumedSecondsTotal(),
                plan.getDailyLimitSeconds(),
                plan.getExtensionSeconds(),
                plan.remainingSeconds()
        );
    }

    HeartbeatResponse toHeartbeatResponse(SessionLease lease, boolean mustStop) {
        return new HeartbeatResponse(
                lease.getLeaseToken(),
                lease.getRevision(),
                lease.getExpiresAt(),
                lease.getLastHeartbeatAt().plusSeconds(heartbeatIntervalSeconds),
                lease.getConsumedSecondsTotal(),
                lease.getPlan().remainingSeconds(),
                mustStop || lease.getStatus() != LeaseStatus.ACTIVE
        );
    }

    public record LeaseGrantedPayload(
            UUID leaseId, UUID memberId, UUID planId, String token,
            int grantedSeconds, Instant grantedAt, Instant expiresAt, String nodeId
    ) {}

    public record LeaseTerminatedPayload(
            UUID leaseId, UUID memberId, UUID planId,
            int consumedSeconds, Instant terminatedAt, String reason
    ) {}

    public record HeartbeatPayload(
            UUID leaseId, UUID memberId, UUID planId,
            int incrementSeconds, int consumedSecondsTotal, Instant at,
            Instant expiresAt, long revision
    ) {}

    public record PlanOpenedPayload(
            UUID planId, UUID memberId, java.time.LocalDate planDate,
            int dailyLimitSeconds, Instant openedAt
    ) {}
}
