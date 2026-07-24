package com.family.scrollshield.service;

import com.family.scrollshield.domain.AgeGroup;
import com.family.scrollshield.domain.Member;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRulesException;
import org.springframework.stereotype.Component;

@Component
public class QuotaPolicyService {

    public static final int ADULT_DAILY_SECONDS = 60 * 60;
    public static final int ADULT_SESSION_SECONDS = 15 * 60;
    public static final int TEEN_DAILY_SECONDS = 30 * 60;
    public static final int CHILD_SESSION_SECONDS = 10 * 60;
    public static final int TEEN_BEDTIME_BLACKOUT_MINUTES = 60;
    public static final int EXTENSION_MAX_SECONDS = 5 * 60;
    public static final int EXTENSION_MAX_PER_DAY = 1;

    private final Clock clock;

    public QuotaPolicyService(Clock clock) {
        this.clock = clock;
    }

    public ZoneId resolveZone(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (ZoneRulesException e) {
            throw LeaseException.invalidTimeZone(timeZone);
        }
    }

    public int dailyLimitSeconds(AgeGroup ageGroup) {
        return switch (ageGroup) {
            case ADULT -> ADULT_DAILY_SECONDS;
            case TEEN -> TEEN_DAILY_SECONDS;
            case CHILD -> ADULT_DAILY_SECONDS;
        };
    }

    public int sessionLimitSeconds(AgeGroup ageGroup) {
        return switch (ageGroup) {
            case ADULT -> ADULT_SESSION_SECONDS;
            case TEEN -> TEEN_DAILY_SECONDS;
            case CHILD -> CHILD_SESSION_SECONDS;
        };
    }

    public LocalDate localDateFor(Member member, Instant at) {
        ZoneId zone = resolveZone(member.getTimeZone());
        return at.atZone(zone).toLocalDate();
    }

    public LocalTime localTimeFor(Member member, Instant at) {
        ZoneId zone = resolveZone(member.getTimeZone());
        return at.atZone(zone).toLocalTime();
    }

    public ZonedDateTime startOfDay(Member member, LocalDate date) {
        ZoneId zone = resolveZone(member.getTimeZone());
        return date.atStartOfDay(zone);
    }

    public boolean isWithinBedtimeBlackout(Member member, Instant at) {
        if (member.getAgeGroup() != AgeGroup.TEEN) return false;
        LocalTime bedtime = member.getBedtimeLocal();
        if (bedtime == null) return false;
        ZoneId zone = resolveZone(member.getTimeZone());
        LocalTime nowLocal = at.atZone(zone).toLocalTime();
        LocalTime blackoutStart = bedtime.minusMinutes(TEEN_BEDTIME_BLACKOUT_MINUTES);
        if (blackoutStart.isBefore(bedtime)) {
            return !nowLocal.isBefore(blackoutStart) && nowLocal.isBefore(bedtime);
        } else {
            return !nowLocal.isBefore(blackoutStart) || nowLocal.isBefore(bedtime);
        }
    }

    public Instant bedtimeBoundary(Member member, Instant at) {
        if (member.getAgeGroup() != AgeGroup.TEEN) return null;
        LocalTime bedtime = member.getBedtimeLocal();
        if (bedtime == null) return null;
        ZoneId zone = resolveZone(member.getTimeZone());
        ZonedDateTime atZ = at.atZone(zone);
        ZonedDateTime bedToday = atZ.toLocalDate().atTime(bedtime).atZone(zone);
        if (!bedToday.toLocalTime().isBefore(bedtime.minusMinutes(TEEN_BEDTIME_BLACKOUT_MINUTES)) &&
                bedToday.isAfter(atZ.minusMinutes(TEEN_BEDTIME_BLACKOUT_MINUTES))) {
            // within 60 min before bed
        }
        ZonedDateTime blackoutStart = atZ.toLocalDate()
                .atTime(bedtime.minusMinutes(TEEN_BEDTIME_BLACKOUT_MINUTES))
                .atZone(zone);
        if (blackoutStart.isAfter(atZ)) {
            return blackoutStart.toInstant();
        }
        return blackoutStart.toInstant();
    }

    public Instant nextBlackoutStart(Member member, Instant at) {
        if (member.getAgeGroup() != AgeGroup.TEEN) return null;
        LocalTime bedtime = member.getBedtimeLocal();
        if (bedtime == null) return null;
        ZoneId zone = resolveZone(member.getTimeZone());
        LocalTime blackoutStart = bedtime.minusMinutes(TEEN_BEDTIME_BLACKOUT_MINUTES);
        ZonedDateTime atZ = at.atZone(zone);
        ZonedDateTime today = atZ.toLocalDate().atTime(blackoutStart).atZone(zone);
        if (!today.isAfter(atZ)) {
            today = today.plusDays(1);
        }
        return today.toInstant();
    }

    public Instant proposedLeaseExpiry(Member member, Instant grantedAt, int sessionSeconds) {
        Instant normalExpiry = grantedAt.plusSeconds(sessionSeconds);
        if (member.getAgeGroup() == AgeGroup.TEEN && member.getBedtimeLocal() != null) {
            Instant blackout = nextBlackoutStart(member, grantedAt);
            if (blackout != null && blackout.isBefore(normalExpiry)) {
                return blackout;
            }
        }
        return normalExpiry;
    }

    public void assertCanGrant(Member member, Instant at, int remainingToday) {
        if (member.getAgeGroup() == AgeGroup.TEEN && isWithinBedtimeBlackout(member, at)) {
            throw LeaseException.bedtimeWindow();
        }
        if (remainingToday <= 0) {
            throw LeaseException.dailyQuotaExhausted();
        }
    }

    public int computeSessionGrantSeconds(Member member, int remainingToday, Instant grantedAt) {
        int single = sessionLimitSeconds(member.getAgeGroup());
        int byRemaining = Math.min(single, remainingToday);
        Instant bySingle = proposedLeaseExpiry(member, grantedAt, single);
        int cappedByBedtime = (int) Duration.between(grantedAt, bySingle).getSeconds();
        return Math.max(1, Math.min(byRemaining, cappedByBedtime));
    }

    public boolean crossesMidnight(Member member, Instant start, Instant end) {
        ZoneId zone = resolveZone(member.getTimeZone());
        LocalDate d1 = start.atZone(zone).toLocalDate();
        LocalDate d2 = end.atZone(zone).toLocalDate();
        return !d1.equals(d2);
    }

    public Instant instantOfNextMidnight(Member member, Instant after) {
        ZoneId zone = resolveZone(member.getTimeZone());
        ZonedDateTime z = after.atZone(zone);
        ZonedDateTime nextMidnight = z.toLocalDate().plusDays(1).atStartOfDay(zone);
        return nextMidnight.toInstant();
    }

    public int clampExtensionToBedtime(Member member, Instant at, int requestedSeconds) {
        if (member.getAgeGroup() != AgeGroup.TEEN || member.getBedtimeLocal() == null) {
            return requestedSeconds;
        }
        Instant blackout = nextBlackoutStart(member, at);
        if (blackout == null) return requestedSeconds;
        long secondsUntilBlackout = Duration.between(at, blackout).getSeconds();
        if (secondsUntilBlackout < requestedSeconds) {
            throw LeaseException.extensionBlockedByBedtime();
        }
        return requestedSeconds;
    }
}
