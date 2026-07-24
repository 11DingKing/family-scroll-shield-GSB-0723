package com.family.scrollshield.controller;

import com.family.scrollshield.dto.response.DailyUsageResponse;
import com.family.scrollshield.service.UsageQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageQueryService usageQueryService;

    @GetMapping("/{memberId}/today")
    public DailyUsageResponse getTodayUsage(@PathVariable UUID memberId) {
        return usageQueryService.getTodayUsage(memberId);
    }

    @GetMapping("/{memberId}")
    public DailyUsageResponse getUsageForDate(
            @PathVariable UUID memberId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return usageQueryService.getUsageForDate(memberId, date);
    }
}
