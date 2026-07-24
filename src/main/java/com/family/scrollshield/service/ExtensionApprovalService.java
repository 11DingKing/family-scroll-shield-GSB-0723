package com.family.scrollshield.service;

import com.family.scrollshield.domain.*;
import com.family.scrollshield.dto.request.ApproveExtensionRequest;
import com.family.scrollshield.dto.request.RequestExtensionRequest;
import com.family.scrollshield.dto.response.ExtensionResponse;
import com.family.scrollshield.exception.*;
import com.family.scrollshield.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtensionApprovalService {

    private final ExtensionApprovalRepository approvalRepository;
    private final FamilyMemberRepository memberRepository;
    private final SessionLeaseRepository leaseRepository;
    private final DailyUsageRepository dailyUsageRepository;
    private final QuotaService quotaService;
    private final TimezoneService timezoneService;
    private final OutboxService outboxService;

    @Transactional
    public ExtensionResponse requestExtension(RequestExtensionRequest request) {
        FamilyMember member = memberRepository.findByMemberUuid(request.memberId())
                .orElseThrow(() -> new MemberNotFoundException(request.memberId()));

        Optional<ExtensionApproval> existing = approvalRepository.findByMemberIdAndIdempotencyKey(member.getId(), request.idempotencyKey());
        if (existing.isPresent()) {
            ExtensionApproval dup = existing.get();
            throw new IdempotentDuplicateException("Duplicate extension request: " + request.idempotencyKey(),
                    toResponse(dup));
        }

        SessionLease lease = null;
        if (request.leaseToken() != null) {
            lease = leaseRepository.findByLeaseToken(request.leaseToken())
                    .orElseThrow(() -> new LeaseNotFoundException(request.leaseToken()));
            if (lease.getStatus() != LeaseStatus.ACTIVE) {
                throw new InvalidLeaseTokenException(request.leaseToken());
            }
        }

        Instant now = Instant.now();
        ZoneId zoneId = timezoneService.getZoneId(member.getTimezone());
        LocalDate today = timezoneService.getLocalDate(now, zoneId);
        Instant dayStart = timezoneService.getDayStart(today, zoneId);
        Instant dayEnd = timezoneService.getDayEnd(today, zoneId);

        long existingExtensions = approvalRepository.countApprovedExtensionsInWindow(member.getId(), dayStart, dayEnd);
        if (existingExtensions >= QuotaService.MAX_EXTENSIONS_PER_DAY) {
            throw new ExtensionDeniedException("Maximum of " + QuotaService.MAX_EXTENSIONS_PER_DAY + " extension(s) per day already reached");
        }

        int requestedMin = Math.min(request.requestedMinutes() != null ? request.requestedMinutes() : 5,
                QuotaService.MAX_EXTENSION_MINUTES);

        if (quotaService.wouldExtensionBreakBedtime(member, now, zoneId, requestedMin)) {
            throw new BedtimeWindowException("Extension would extend into bedtime restriction window");
        }

        ExtensionApproval approval = ExtensionApproval.builder()
                .approvalToken(UUID.randomUUID())
                .member(member)
                .lease(lease)
                .idempotencyKey(request.idempotencyKey())
                .status(ExtensionStatus.PENDING)
                .requestedMinutes(requestedMin)
                .requestedAt(now)
                .reason(request.reason())
                .build();

        approval = approvalRepository.save(approval);

        outboxService.recordEvent("ExtensionApproval", approval.getApprovalToken().toString(), "EXTENSION_REQUESTED",
                new ExtensionOutboxPayload(member.getMemberUuid(), approval.getApprovalToken(), requestedMin, now));

        log.info("Extension requested: member={} token={} minutes={}", member.getMemberUuid(), approval.getApprovalToken(), requestedMin);

        return toResponse(approval);
    }

    @Transactional
    public ExtensionResponse approveExtension(UUID approvalToken, ApproveExtensionRequest request) {
        ExtensionApproval approval = approvalRepository.findByTokenForUpdate(approvalToken)
                .orElseThrow(() -> new ExtensionNotFoundException(approvalToken));

        if (approval.getStatus() != ExtensionStatus.PENDING) {
            return toResponse(approval);
        }

        Instant now = Instant.now();
        FamilyMember member = approval.getMember();
        ZoneId zoneId = timezoneService.getZoneId(member.getTimezone());

        if (!request.approved()) {
            approval.setStatus(ExtensionStatus.REJECTED);
            approval.setDecidedAt(now);
            approvalRepository.save(approval);

            outboxService.recordEvent("ExtensionApproval", approval.getApprovalToken().toString(), "EXTENSION_REJECTED",
                    new ExtensionOutboxPayload(member.getMemberUuid(), approval.getApprovalToken(), 0, now));

            return toResponse(approval);
        }

        LocalDate today = timezoneService.getLocalDate(now, zoneId);
        Instant dayStart = timezoneService.getDayStart(today, zoneId);
        Instant dayEnd = timezoneService.getDayEnd(today, zoneId);

        long existingExtensions = approvalRepository.countApprovedExtensionsInWindow(member.getId(), dayStart, dayEnd);
        if (existingExtensions >= QuotaService.MAX_EXTENSIONS_PER_DAY) {
            approval.setStatus(ExtensionStatus.REJECTED);
            approval.setDecidedAt(now);
            approval.setReason("Daily extension limit reached between request and approval");
            approvalRepository.save(approval);
            return toResponse(approval);
        }

        int grantedMin = approval.getRequestedMinutes();

        DailyUsage usage = dailyUsageRepository.findByMemberAndDateForUpdate(member.getId(), today).orElse(null);
        if (usage == null) {
            throw new ExtensionDeniedException("No daily usage record found for today");
        }

        if (quotaService.wouldExtensionBreakBedtime(member, now, zoneId, grantedMin)) {
            approval.setStatus(ExtensionStatus.REJECTED);
            approval.setDecidedAt(now);
            approval.setReason("Extension would break bedtime window");
            approvalRepository.save(approval);
            return toResponse(approval);
        }

        if (usage.getRemainingMinutes() < grantedMin) {
            approval.setStatus(ExtensionStatus.REJECTED);
            approval.setDecidedAt(now);
            approval.setReason("Insufficient remaining daily quota for extension");
            approvalRepository.save(approval);
            return toResponse(approval);
        }

        usage.setUsedMinutes(usage.getUsedMinutes() + grantedMin);
        usage.setRemainingMinutes(usage.getRemainingMinutes() - grantedMin);
        usage.setExtensionsUsed(usage.getExtensionsUsed() + 1);
        dailyUsageRepository.save(usage);

        approval.setStatus(ExtensionStatus.APPROVED);
        approval.setGrantedMinutes(grantedMin);
        approval.setDecidedAt(now);
        approvalRepository.save(approval);

        if (approval.getLease() != null && approval.getLease().getStatus() == LeaseStatus.ACTIVE) {
            SessionLease lease = approval.getLease();
            lease.setExpiresAt(lease.getExpiresAt().plusSeconds(grantedMin * 60L));
            lease.setGrantedMinutes(lease.getGrantedMinutes() + grantedMin);
            leaseRepository.save(lease);
        }

        outboxService.recordEvent("ExtensionApproval", approval.getApprovalToken().toString(), "EXTENSION_APPROVED",
                new ExtensionOutboxPayload(member.getMemberUuid(), approval.getApprovalToken(), grantedMin, now));

        log.info("Extension approved: member={} token={} granted={}min", member.getMemberUuid(), approvalToken, grantedMin);

        return toResponse(approval);
    }

    @Transactional
    public ExtensionResponse getApproval(UUID approvalToken) {
        ExtensionApproval approval = approvalRepository.findByApprovalToken(approvalToken)
                .orElseThrow(() -> new ExtensionNotFoundException(approvalToken));
        return toResponse(approval);
    }

    private ExtensionResponse toResponse(ExtensionApproval a) {
        return new ExtensionResponse(
                a.getApprovalToken(),
                a.getMember().getMemberUuid(),
                a.getStatus().name(),
                a.getRequestedMinutes(),
                a.getGrantedMinutes(),
                a.getRequestedAt(),
                a.getDecidedAt(),
                a.getReason()
        );
    }

    public record ExtensionOutboxPayload(
            UUID memberId,
            UUID approvalToken,
            Integer minutes,
            Instant timestamp
    ) {}

    public static class ExtensionNotFoundException extends ScrollShieldException {
        public ExtensionNotFoundException(UUID token) {
            super("Extension approval not found: " + token, "EXTENSION_NOT_FOUND");
        }
    }
}
