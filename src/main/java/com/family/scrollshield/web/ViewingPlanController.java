package com.family.scrollshield.web;

import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.dto.PlanResponse;
import com.family.scrollshield.service.MemberService;
import com.family.scrollshield.service.QuotaPolicyService;
import com.family.scrollshield.service.ViewingPlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/plans")
public class ViewingPlanController {

    private final ViewingPlanService planService;
    private final MemberService memberService;
    private final QuotaPolicyService quotaPolicy;

    public ViewingPlanController(ViewingPlanService planService,
                                 MemberService memberService,
                                 QuotaPolicyService quotaPolicy) {
        this.planService = planService;
        this.memberService = memberService;
        this.quotaPolicy = quotaPolicy;
    }

    /** Today's plan (member-local date) for a member. */
    @GetMapping("/{memberExternalId}/today")
    public PlanResponse today(@PathVariable String memberExternalId) {
        Member member = memberService.requireByExternalId(memberExternalId);
        LocalDate planDate = quotaPolicy.currentPlanDate(member);
        ViewingPlan plan = planService.findPlan(member.getId(), planDate)
                .orElseGet(() -> emptyProjection(member, planDate));
        return PlanResponse.from(plan);
    }

    private ViewingPlan emptyProjection(Member member, LocalDate planDate) {
        return ViewingPlan.builder()
                .memberId(member.getId())
                .planDate(planDate)
                .dailyLimitSeconds(quotaPolicy.dailyLimitSeconds(member.getAgeGroup()))
                .consumedSeconds(0)
                .extensionSeconds(0)
                .extensionUsed(false)
                .status(com.family.scrollshield.domain.PlanStatus.OPEN)
                .build();
    }
}
