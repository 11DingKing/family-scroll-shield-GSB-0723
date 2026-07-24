package com.family.scrollshield.service;

import com.family.scrollshield.domain.FamilyMember;
import com.family.scrollshield.domain.MemberRole;
import org.springframework.stereotype.Service;

import java.time.*;

@Service
public class QuotaService {

    public static final int ADULT_DAILY_LIMIT = 60;
    public static final int ADULT_SINGLE_LIMIT = 15;
    public static final int TEEN_DAILY_LIMIT = 30;
    public static final int TEEN_SINGLE_LIMIT = 15;
    public static final int CHILD_DAILY_LIMIT = 30;
    public static final int CHILD_SINGLE_LIMIT = 10;
    public static final int TEEN_BEDTIME_BEFORE_MINUTES = 60;
    public static final int MAX_EXTENSION_MINUTES = 5;
    public static final int MAX_EXTENSIONS_PER_DAY = 1;

    public int getDailyLimit(FamilyMember member) {
        if (member.getDailyLimitMin() != null) {
            return member.getDailyLimitMin();
        }
        return switch (member.getRole()) {
            case ADULT -> ADULT_DAILY_LIMIT;
            case TEEN -> TEEN_DAILY_LIMIT;
            case CHILD -> CHILD_DAILY_LIMIT;
        };
    }

    public int getSingleSessionLimit(FamilyMember member) {
        if (member.getSingleLimitMin() != null) {
            return member.getSingleLimitMin();
        }
        return switch (member.getRole()) {
            case ADULT -> ADULT_SINGLE_LIMIT;
            case TEEN -> TEEN_SINGLE_LIMIT;
            case CHILD -> CHILD_SINGLE_LIMIT;
        };
    }

    public boolean isBedtimeRestricted(FamilyMember member, Instant now, ZoneId zoneId) {
        if (member.getRole() == MemberRole.TEEN && member.getBedtimeLocal() != null) {
            return isWithinBedtimeWindow(now, member.getBedtimeLocal(), zoneId, TEEN_BEDTIME_BEFORE_MINUTES);
        }
        return false;
    }

    public boolean wouldExtensionBreakBedtime(FamilyMember member, Instant now, ZoneId zoneId, int extensionMinutes) {
        if (member.getRole() != MemberRole.TEEN || member.getBedtimeLocal() == null) {
            return false;
        }
        LocalTime bedtime = member.getBedtimeLocal();
        ZonedDateTime zonedNow = now.atZone(zoneId);
        LocalDate today = zonedNow.toLocalDate();
        ZonedDateTime bedtimeToday = bedtime.atDate(today).atZone(zoneId);
        ZonedDateTime extendedEnd = zonedNow.plusMinutes(extensionMinutes);
        return extendedEnd.isAfter(bedtimeToday);
    }

    private boolean isWithinBedtimeWindow(Instant now, LocalTime bedtime, ZoneId zoneId, int beforeMinutes) {
        ZonedDateTime zonedNow = now.atZone(zoneId);
        LocalDate today = zonedNow.toLocalDate();
        ZonedDateTime bedtimeToday = bedtime.atDate(today).atZone(zoneId);
        ZonedDateTime windowStart = bedtimeToday.minusMinutes(beforeMinutes);
        ZonedDateTime dayStart = today.atStartOfDay(zoneId);

        if (zonedNow.isBefore(dayStart)) {
            return true;
        }
        return !zonedNow.isBefore(windowStart) && zonedNow.isBefore(bedtimeToday);
    }

    public int calculateGrantedMinutes(FamilyMember member, int requestedMinutes, int remainingDailyMinutes, Instant now, ZoneId zoneId) {
        int singleLimit = getSingleSessionLimit(member);
        int byDaily = Math.max(0, remainingDailyMinutes);
        int bySingle = singleLimit;
        int byRequest = Math.max(1, requestedMinutes);
        return Math.min(Math.min(byDaily, bySingle), byRequest);
    }
}
