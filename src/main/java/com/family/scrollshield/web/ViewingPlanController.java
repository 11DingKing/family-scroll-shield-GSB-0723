package com.family.scrollshield.web;

import com.family.scrollshield.dto.PlanResponse;
import com.family.scrollshield.service.ViewingPlanService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class ViewingPlanController {

    private final ViewingPlanService planService;

    @GetMapping("/member/{memberId}/today")
    public PlanResponse today(@PathVariable UUID memberId) {
        return planService.todayFor(memberId);
    }

    @GetMapping("/{planId}")
    public PlanResponse get(@PathVariable UUID planId) {
        return planService.get(planId);
    }

    @GetMapping("/member/{memberId}")
    public List<PlanResponse> history(
            @PathVariable UUID memberId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return planService.history(memberId, from, to);
    }
}
