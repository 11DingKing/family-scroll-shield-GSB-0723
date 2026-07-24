package com.family.scrollshield.service;

import com.family.scrollshield.domain.ExtensionApproval;
import com.family.scrollshield.domain.LeaseStatus;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.repository.ExtensionApprovalRepository;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.ViewingPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Grants bedtime-safe daily extensions. Rules:
 * <ul>
 *   <li>At most one extension per plan/day (guarded by the locked plan's
 *       {@code extensionUsed} flag).</li>
 *   <li>Grant is at most 5 minutes and is clamped so viewing can never cross the
 *       bedtime blackout window.</li>
 *   <li>The extension increases the authoritative daily budget even when the member
 *       has no active lease; if an active lease exists, its session window is widened
 *       to consume the freshly granted time.</li>
 * </ul>
 * The plan mutation, the approval audit row, and the outbox event all commit atomically.
 */
@Service
public class ExtensionApprovalService {

    private final ExtensionApprovalRepository approvalRepository;
    private final ViewingPlanRepository planRepository;
    private final SessionLeaseRepository leaseRepository;
    private final ViewingPlanService planService;
    private final MemberService memberService;
    private final QuotaPolicyService quotaPolicy;
    private final LeaseRedisCache cache;
    private final OutboxService outbox;

    public ExtensionApprovalService(ExtensionApprovalRepository approvalRepository,
                                    ViewingPlanRepository planRepository,
                                    SessionLeaseRepository leaseRepository,
                                    ViewingPlanService planService,
                                    MemberService memberService,
                                    QuotaPolicyService quotaPolicy,
                                    LeaseRedisCache cache,
                                    OutboxService outbox) {
        this.approvalRepository = approvalRepository;
        this.planRepository = planRepository;
        this.leaseRepository = leaseRepository;
        this.planService = planService;
        this.memberService = memberService;
        this.quotaPolicy = quotaPolicy;
        this.cache = cache;
        this.outbox = outbox;
    }

    public record ExtensionGrantedPayload(UUID approvalId, UUID memberId, UUID planId, UUID leaseId,
                                          String approver, int grantedSeconds, OffsetDateTime grantedAt,
                                          OffsetDateTime expiresAt, String reason) {
    }

    public record LeaseExtendedPayload(UUID leaseId, UUID memberId, int newSessionGrantedSeconds,
                                       OffsetDateTime newExpiresAt) {
    }

    @Transactional
    public ExtensionApproval approve(String memberExternalId, String approver, String reason) {
        Member member = memberService.requireByExternalId(memberExternalId);
        Instant now = quotaPolicy.now();
        LocalDate planDate = quotaPolicy.memberLocalDate(member, now);

        // Lock the authoritative plan row so the once-per-day rule is race-free.
        ViewingPlan plan = planService.getOrCreatePlan(member, planDate);
        plan = planRepository.lockById(plan.getId()).orElseThrow();

        QuotaPolicyService.ExtensionGrant grant = quotaPolicy.computeExtension(member, plan, now);

        plan.setExtensionUsed(true);
        plan.setExtensionSeconds(plan.getExtensionSeconds() + grant.grantedSeconds());
        planRepository.save(plan);

        // Widen an active lease's session budget so the member can actually use the grant.
        Optional<SessionLease> active = leaseRepository.lockActiveByMember(member.getId());
        UUID leaseId = null;
        if (active.isPresent()) {
            SessionLease lease = active.get();
            leaseId = lease.getId();
            int newSessionGrant = lease.getSessionGrantedSeconds() + grant.grantedSeconds();
            lease.setSessionGrantedSeconds(newSessionGrant);
            OffsetDateTime newExpiry = quotaPolicy.cappedExpiry(member, now,
                    newSessionGrant - lease.getConsumedSecondsTotal());
            if (newExpiry.toInstant().isAfter(lease.getExpiresAt().toInstant())) {
                lease.setExpiresAt(newExpiry);
            }
            lease.setRevision(lease.getRevision() + 1);
            leaseRepository.save(lease);
            cache.putActive(lease);
            outbox.record(AggregateTypes.LEASE, lease.getId(), EventTypes.LEASE_EXTENDED,
                    new LeaseExtendedPayload(lease.getId(), member.getId(), newSessionGrant,
                            lease.getExpiresAt()));
        }

        ExtensionApproval approval = ExtensionApproval.builder()
                .id(UUID.randomUUID())
                .memberId(member.getId())
                .planId(plan.getId())
                .leaseId(leaseId)
                .approver(approver)
                .grantedSeconds(grant.grantedSeconds())
                .grantedAt(now.atOffset(java.time.ZoneOffset.UTC))
                .expiresAt(grant.expiresAt())
                .reason(reason)
                .build();
        approval = approvalRepository.save(approval);

        outbox.record(AggregateTypes.EXTENSION, approval.getId(), EventTypes.EXTENSION_GRANTED,
                new ExtensionGrantedPayload(approval.getId(), member.getId(), plan.getId(), leaseId,
                        approver, grant.grantedSeconds(), approval.getGrantedAt(),
                        approval.getExpiresAt(), reason));
        return approval;
    }
}
