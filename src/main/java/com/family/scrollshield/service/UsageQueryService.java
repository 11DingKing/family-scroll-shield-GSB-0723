package com.family.scrollshield.service;

import com.family.scrollshield.domain.DailyUsage;
import com.family.scrollshield.domain.FamilyMember;
import com.family.scrollshield.dto.response.DailyUsageResponse;
import com.family.scrollshield.exception.MemberNotFoundException;
import com.family.scrollshield.repository.DailyUsageRepository;
import com.family.scrollshield.repository.FamilyMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UsageQueryService {

    private final DailyUsageRepository dailyUsageRepository;
    private final FamilyMemberRepository memberRepository;
    private final TimezoneService timezoneService;

    @Transactional(readOnly = true)
    public DailyUsageResponse getTodayUsage(UUID memberUuid) {
        FamilyMember member = memberRepository.findByMemberUuid(memberUuid)
                .orElseThrow(() -> new MemberNotFoundException(memberUuid));

        ZoneId zoneId = timezoneService.getZoneId(member.getTimezone());
        LocalDate today = timezoneService.getLocalDate(Instant.now(), zoneId);

        DailyUsage usage = dailyUsageRepository.findByMemberIdAndUsageDate(member.getId(), today).orElse(null);

        if (usage == null) {
            return new DailyUsageResponse(
                    member.getMemberUuid(),
                    today,
                    member.getDailyLimitMin(),
                    0,
                    member.getDailyLimitMin(),
                    0
            );
        }

        return new DailyUsageResponse(
                member.getMemberUuid(),
                usage.getUsageDate(),
                usage.getDailyLimitMin(),
                usage.getUsedMinutes(),
                usage.getRemainingMinutes(),
                usage.getExtensionsUsed()
        );
    }

    @Transactional(readOnly = true)
    public DailyUsageResponse getUsageForDate(UUID memberUuid, LocalDate date) {
        FamilyMember member = memberRepository.findByMemberUuid(memberUuid)
                .orElseThrow(() -> new MemberNotFoundException(memberUuid));

        DailyUsage usage = dailyUsageRepository.findByMemberIdAndUsageDate(member.getId(), date).orElse(null);

        if (usage == null) {
            return new DailyUsageResponse(
                    member.getMemberUuid(),
                    date,
                    member.getDailyLimitMin(),
                    0,
                    member.getDailyLimitMin(),
                    0
            );
        }

        return new DailyUsageResponse(
                member.getMemberUuid(),
                usage.getUsageDate(),
                usage.getDailyLimitMin(),
                usage.getUsedMinutes(),
                usage.getRemainingMinutes(),
                usage.getExtensionsUsed()
        );
    }
}
