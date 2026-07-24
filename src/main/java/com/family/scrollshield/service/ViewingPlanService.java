package com.family.scrollshield.service;

import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.repository.ViewingPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the authoritative daily viewing plan (budget ledger). Plans are created
 * lazily, one per member-local date, and are the durable source of truth for
 * consumed time. Redis never holds authoritative consumption.
 */
@Service
public class ViewingPlanService {

    private final ViewingPlanRepository planRepository;
    private final QuotaPolicyService quotaPolicy;
    private final OutboxService outbox;

    public ViewingPlanService(ViewingPlanRepository planRepository,
                              QuotaPolicyService quotaPolicy,
                              OutboxService outbox) {
        this.planRepository = planRepository;
        this.quotaPolicy = quotaPolicy;
        this.outbox = outbox;
    }

    public record PlanOpenedPayload(UUID planId, UUID memberId, LocalDate planDate,
                                    int dailyLimitSeconds) {
    }

    /**
     * Fetch the member's plan for the given member-local date, creating it if absent.
     * Safe against concurrent creators: the insert uses {@code ON CONFLICT DO NOTHING}
     * so a race never aborts the transaction, and the row is then read back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ViewingPlan getOrCreatePlan(Member member, LocalDate planDate) {
        Optional<ViewingPlan> existing = planRepository.findByMemberIdAndPlanDate(member.getId(), planDate);
        if (existing.isPresent()) {
            return existing.get();
        }
        int dailyLimit = quotaPolicy.dailyLimitSeconds(member.getAgeGroup());
        UUID newId = UUID.randomUUID();
        int inserted = planRepository.insertIfAbsent(newId, member.getId(), planDate, dailyLimit);
        if (inserted == 1) {
            // We created it: record the plan-opened event for audit/outbox.
            outbox.record(AggregateTypes.PLAN, newId, EventTypes.PLAN_OPENED,
                    new PlanOpenedPayload(newId, member.getId(), planDate, dailyLimit));
        }
        // Whether we created it or lost the race, the row now exists; read it back.
        return planRepository.findByMemberIdAndPlanDate(member.getId(), planDate)
                .orElseThrow(() -> new IllegalStateException("plan row missing after upsert"));
    }

    @Transactional(readOnly = true)
    public Optional<ViewingPlan> findPlan(UUID memberId, LocalDate planDate) {
        return planRepository.findByMemberIdAndPlanDate(memberId, planDate);
    }

    @Transactional(readOnly = true)
    public int remainingByPlanId(UUID planId) {
        return planRepository.findById(planId)
                .map(ViewingPlan::remainingSeconds)
                .orElse(0);
    }
}
