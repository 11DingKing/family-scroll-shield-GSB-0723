package com.family.scrollshield.service;

import com.family.scrollshield.domain.ExtensionApproval;
import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.PlanStatus;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.dto.ExtensionRequest;
import com.family.scrollshield.dto.ExtensionResponse;
import com.family.scrollshield.repository.ExtensionApprovalRepository;
import com.family.scrollshield.repository.MemberRepository;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.ViewingPlanRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtensionApprovalService {

    private final ExtensionApprovalRepository approvalRepository;
    private final ViewingPlanRepository planRepository;
    private final SessionLeaseRepository leaseRepository;
    private final MemberRepository memberRepository;
    private final QuotaPolicyService policy;
    private final OutboxService outbox;
    private final LeaseRedisCache redisCache;
    private final Clock clock;

    @Transactional
    public ExtensionResponse grant(ExtensionRequest req) {
        Instant now = Instant.now(clock);
        Member member = memberRepository.findById(req.memberId())
                .orElseThrow(LeaseException::memberNotFound);

        java.time.LocalDate today = policy.localDateFor(member, now);
        ViewingPlan plan = planRepository.lockByMemberAndDate(member.getId(), today)
                .orElseThrow(() -> new LeaseException("PLAN_NOT_FOUND", "今日尚无观看计划，无法延长"));

        if (plan.getStatus() == PlanStatus.CLOSED) {
            throw LeaseException.planClosed();
        }
        if (plan.isExtensionUsed()) {
            throw LeaseException.extensionAlreadyUsed();
        }
        if (approvalRepository.countByPlan(plan.getId()) > 0) {
            throw LeaseException.extensionAlreadyUsed();
        }

        int requested = QuotaPolicyService.EXTENSION_MAX_SECONDS;
        int granted;
        try {
            granted = policy.clampExtensionToBedtime(member, now, requested);
        } catch (LeaseException e) {
            throw e;
        }
        if (granted < 60) {
            throw LeaseException.extensionBlockedByBedtime();
        }

        Instant expiresAt = now.plusSeconds(granted);

        SessionLease lease = null;
        if (req.leaseId() != null) {
            Optional<SessionLease> opt = leaseRepository.lockById(req.leaseId());
            if (opt.isPresent() && opt.get().getStatus() == LeaseStatus.ACTIVE
                    && opt.get().getMember().getId().equals(member.getId())) {
                lease = opt.get();
                Instant newLeaseExpiry = lease.getExpiresAt().plusSeconds(granted);
                if (member.getAgeGroup() == com.family.scrollshield.domain.AgeGroup.TEEN
                        && member.getBedtimeLocal() != null) {
                    Instant blackout = policy.nextBlackoutStart(member, now);
                    if (blackout != null && newLeaseExpiry.isAfter(blackout)) {
                        newLeaseExpiry = blackout;
                    }
                }
                lease.setExpiresAt(newLeaseExpiry);
                lease.setRevision(lease.getRevision() + 1);
            }
        }

        plan.setExtensionUsed(true);
        plan.setExtensionSeconds(plan.getExtensionSeconds() + granted);

        ExtensionApproval approval = ExtensionApproval.builder()
                .id(UUID.randomUUID())
                .member(member)
                .plan(plan)
                .lease(lease)
                .approver(req.approver() == null ? "parent" : req.approver())
                .grantedSeconds(granted)
                .grantedAt(now)
                .expiresAt(expiresAt)
                .reason(req.reason())
                .build();
        approvalRepository.save(approval);

        outbox.record(AggregateTypes.EXTENSION, approval.getId(), EventTypes.EXTENSION_GRANTED,
                new ExtensionGrantedPayload(approval.getId(), member.getId(), plan.getId(),
                        lease == null ? null : lease.getId(), granted, now, expiresAt,
                        approval.getApprover()));

        if (lease != null) {
            outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_EXTENDED,
                    new LeaseExtendedPayload(lease.getId(), member.getId(), plan.getId(),
                            granted, lease.getExpiresAt(), lease.getRevision(), now));
            SessionLease capturedLease = lease;
            registerAfterCommit(() -> redisCache.refresh(
                    capturedLease.getLeaseToken(), member.getId(),
                    capturedLease.getExpiresAt(), capturedLease.getRevision()));
        }

        return new ExtensionResponse(
                approval.getId(),
                member.getId(),
                plan.getId(),
                granted,
                now,
                expiresAt,
                lease != null
        );
    }

    private void registerAfterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try { r.run(); } catch (Exception e) {
                        log.warn("After-commit cache refresh failed: {}", e.getMessage());
                    }
                }
            });
        }
    }

    public record ExtensionGrantedPayload(
            UUID approvalId, UUID memberId, UUID planId, UUID leaseId,
            int grantedSeconds, Instant grantedAt, Instant expiresAt, String approver
    ) {}

    public record LeaseExtendedPayload(
            UUID leaseId, UUID memberId, UUID planId,
            int grantedSeconds, Instant newExpiresAt, long revision, Instant at
    ) {}
}
