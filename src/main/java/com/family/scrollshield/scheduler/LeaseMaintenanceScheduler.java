package com.family.scrollshield.scheduler;

import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.PlanStatus;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.repository.MemberRepository;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.ViewingPlanRepository;
import com.family.scrollshield.service.AggregateTypes;
import com.family.scrollshield.service.EventTypes;
import com.family.scrollshield.service.LeaseRedisCache;
import com.family.scrollshield.service.OutboxService;
import com.family.scrollshield.service.QuotaPolicyService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class LeaseMaintenanceScheduler {

    private final SessionLeaseRepository leaseRepository;
    private final ViewingPlanRepository planRepository;
    private final MemberRepository memberRepository;
    private final QuotaPolicyService policy;
    private final OutboxService outbox;
    private final LeaseRedisCache redisCache;
    private final Clock clock;

    @Value("${scrollshield.lease.expiry-margin-seconds:90}")
    private long expiryMarginSeconds;

    @Scheduled(fixedDelayString = "${scrollshield.maintenance.expired-scan-ms:10000}")
    @Transactional
    public void expireDeadLeases() {
        Instant now = Instant.now(clock);
        Duration margin = Duration.ofSeconds(expiryMarginSeconds);
        Instant threshold = now.minus(margin);
        List<SessionLease> expired = leaseRepository.findExpiredActive(LeaseStatus.ACTIVE, threshold);
        for (SessionLease lease : expired) {
            try {
                SessionLease locked = leaseRepository.lockById(lease.getId()).orElse(null);
                if (locked == null || locked.getStatus() != LeaseStatus.ACTIVE) continue;
                if (locked.getLastHeartbeatAt().plus(margin).isAfter(now)) continue;

                ViewingPlan plan = planRepository.lockById(locked.getPlan().getId()).orElse(null);
                long wallDelta = Duration.between(locked.getLastHeartbeatAt(), now).getSeconds();
                if (wallDelta < 0) wallDelta = 0;
                int sessionRemaining = locked.getSessionGrantedSeconds() - locked.getConsumedSecondsTotal();
                if (sessionRemaining < 0) sessionRemaining = 0;
                int finalDelta = (int) Math.min(wallDelta, sessionRemaining);
                if (finalDelta > 0 && plan != null) {
                    locked.setConsumedSecondsTotal(locked.getConsumedSecondsTotal() + finalDelta);
                    locked.setLastIncrementSeconds(finalDelta);
                    plan.setConsumedSeconds(plan.getConsumedSeconds() + finalDelta);
                }
                locked.setStatus(LeaseStatus.EXPIRED);
                locked.setRevision(locked.getRevision() + 1);
                outbox.record(AggregateTypes.LEASE, locked.getId(), EventTypes.LEASE_EXPIRED,
                        new LeaseExpiredPayload(locked.getId(), locked.getMember().getId(),
                                locked.getPlan().getId(), locked.getConsumedSecondsTotal(), now,
                                "scheduler_expiry"));
                redisCache.release(locked.getMember().getId(), locked.getLeaseToken());
            } catch (Exception e) {
                log.warn("Failed to expire lease {}: {}", lease.getId(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelayString = "${scrollshield.maintenance.midnight-scan-ms:60000}")
    @Transactional
    public void rolloverCrossedMidnightPlans() {
        Instant now = Instant.now(clock);
        List<ViewingPlan> openPlans = planRepository.findAll().stream()
                .filter(p -> p.getStatus() == PlanStatus.OPEN)
                .toList();
        for (ViewingPlan plan : openPlans) {
            try {
                var member = memberRepository.findById(plan.getMember().getId()).orElse(null);
                if (member == null) continue;
                java.time.LocalDate memberToday = policy.localDateFor(member, now);
                if (!plan.getPlanDate().isBefore(memberToday)) continue;

                ViewingPlan locked = planRepository.lockById(plan.getId()).orElse(null);
                if (locked == null || locked.getStatus() != PlanStatus.OPEN) continue;

                List<SessionLease> active = leaseRepository.findByPlanAndStatuses(
                        locked.getId(), List.of(LeaseStatus.ACTIVE));
                for (SessionLease lease : active) {
                    SessionLease ll = leaseRepository.lockById(lease.getId()).orElse(null);
                    if (ll == null || ll.getStatus() != LeaseStatus.ACTIVE) continue;
                    long wallDelta = Duration.between(ll.getLastHeartbeatAt(), now).getSeconds();
                    if (wallDelta < 0) wallDelta = 0;
                    int sessionRemaining = ll.getSessionGrantedSeconds() - ll.getConsumedSecondsTotal();
                    if (sessionRemaining < 0) sessionRemaining = 0;
                    int finalDelta = (int) Math.min(wallDelta, sessionRemaining);
                    if (finalDelta > 0) {
                        ll.setConsumedSecondsTotal(ll.getConsumedSecondsTotal() + finalDelta);
                        ll.setLastIncrementSeconds(finalDelta);
                        locked.setConsumedSeconds(locked.getConsumedSeconds() + finalDelta);
                    }
                    ll.setStatus(LeaseStatus.CROSSED_MIDNIGHT);
                    ll.setRevision(ll.getRevision() + 1);
                    outbox.record(AggregateTypes.LEASE, ll.getId(), EventTypes.LEASE_CROSSED_MIDNIGHT,
                            new LeaseExpiredPayload(ll.getId(), member.getId(), locked.getId(),
                                    ll.getConsumedSecondsTotal(), now, "scheduler_midnight"));
                    redisCache.release(member.getId(), ll.getLeaseToken());
                }
                locked.setStatus(PlanStatus.CLOSED);
                locked.setClosedAt(now);
                outbox.record(AggregateTypes.PLAN, locked.getId(), EventTypes.PLAN_CLOSED,
                        new PlanClosedPayload(locked.getId(), member.getId(),
                                locked.getPlanDate(), locked.getConsumedSeconds(), now,
                                "midnight_rollover"));
            } catch (Exception e) {
                log.warn("Failed to rollover plan {}: {}", plan.getId(), e.getMessage());
            }
        }
    }

    public record LeaseExpiredPayload(
            java.util.UUID leaseId, java.util.UUID memberId, java.util.UUID planId,
            int consumedSeconds, Instant at, String reason
    ) {}

    public record PlanClosedPayload(
            java.util.UUID planId, java.util.UUID memberId,
            java.time.LocalDate planDate, int consumedSeconds, Instant at, String reason
    ) {}
}
