package com.family.scrollshield.service;

import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.PlanStatus;
import com.family.scrollshield.domain.SessionLease;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.dto.PlanResponse;
import com.family.scrollshield.repository.MemberRepository;
import com.family.scrollshield.repository.SessionLeaseRepository;
import com.family.scrollshield.repository.ViewingPlanRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ViewingPlanService {

    private final ViewingPlanRepository planRepository;
    private final MemberRepository memberRepository;
    private final SessionLeaseRepository leaseRepository;
    private final QuotaPolicyService policy;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PlanResponse todayFor(UUID memberId) {
        Member m = memberRepository.findById(memberId).orElseThrow(LeaseException::memberNotFound);
        Instant now = Instant.now(clock);
        LocalDate today = policy.localDateFor(m, now);
        ViewingPlan plan = planRepository.findByMemberIdAndPlanDate(memberId, today).orElse(null);
        if (plan == null) {
            return new PlanResponse(null, memberId, today,
                    policy.dailyLimitSeconds(m.getAgeGroup()), 0, 0, false,
                    PlanStatus.OPEN.name(), policy.dailyLimitSeconds(m.getAgeGroup()));
        }
        return toResponse(plan);
    }

    @Transactional(readOnly = true)
    public List<PlanResponse> history(UUID memberId, LocalDate from, LocalDate to) {
        return planRepository.findAll().stream()
                .filter(p -> p.getMember().getId().equals(memberId))
                .filter(p -> (from == null || !p.getPlanDate().isBefore(from))
                          && (to == null || !p.getPlanDate().isAfter(to)))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PlanResponse get(UUID planId) {
        return toResponse(planRepository.findById(planId)
                .orElseThrow(() -> new LeaseException("PLAN_NOT_FOUND", "计划不存在")));
    }

    public PlanResponse toResponse(ViewingPlan p) {
        return new PlanResponse(
                p.getId(),
                p.getMember().getId(),
                p.getPlanDate(),
                p.getDailyLimitSeconds(),
                p.getConsumedSeconds(),
                p.getExtensionSeconds(),
                p.isExtensionUsed(),
                p.getStatus().name(),
                p.remainingSeconds()
        );
    }

    @Transactional(readOnly = true)
    public boolean hasOpenPlanBefore(UUID memberId, LocalDate date) {
        return !planRepository.findOpenPlansBefore(memberId, date).isEmpty();
    }

    @Transactional
    public void closePlanIfOpen(ViewingPlan plan, Instant at, String reason) {
        if (plan.getStatus() == PlanStatus.CLOSED) return;
        plan.setStatus(PlanStatus.CLOSED);
        plan.setClosedAt(at);
    }
}
