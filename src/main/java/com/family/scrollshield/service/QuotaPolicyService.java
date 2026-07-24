package com.family.scrollshield.service;

import com.family.scrollshield.domain.AgeGroup;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.ViewingPlan;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Pure, side-effect-free quota policy engine.
 *
 * <p>All day boundaries, bedtime blackout windows, DST transitions and cross-midnight
 * settlement are computed against the member's own IANA time zone, which is the single
 * authoritative reference. This class performs no I/O and holds no state beyond an
 * injected {@link Clock}, so its rules are deterministic and unit-testable.
 *
 * <p>Enforced rules:
 * <ul>
 *   <li>ADULT: 60 min/day, 15 min/session.</li>
 *   <li>TEEN: 30 min/day, plus a "no viewing in the hour before bedtime" blackout.</li>
 *   <li>CHILD: 10 min/session (with a conservative daily cap).</li>
 *   <li>Extension: at most one 5-minute grant per day, never crossing the bedtime window.</li>
 * </ul>
 */
@Service
public class QuotaPolicyService {

    /** Hour of "no viewing" enforced before a member's bedtime. */
    public static final Duration BEDTIME_BLACKOUT = Duration.ofHours(1);

    public static final int ADULT_DAILY_SECONDS = 60 * 60;
    public static final int ADULT_SESSION_SECONDS = 15 * 60;

    public static final int TEEN_DAILY_SECONDS = 30 * 60;
    public static final int TEEN_SESSION_SECONDS = 15 * 60;

    // The requirement fixes the CHILD single session at 10 minutes; the daily cap is a
    // conservative default (three sessions) since only the per-session limit is specified.
    public static final int CHILD_DAILY_SECONDS = 30 * 60;
    public static final int CHILD_SESSION_SECONDS = 10 * 60;

    /** Maximum extension grant per day. */
    public static final int MAX_EXTENSION_SECONDS = 5 * 60;

    private final Clock clock;

    public QuotaPolicyService(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    /** Resolve and validate a member's time zone, or fail fast. */
    public ZoneId zoneOf(Member member) {
        return zoneOf(member.getTimeZone());
    }

    public ZoneId zoneOf(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            throw new LeaseException(LeaseException.Code.INVALID_TIME_ZONE, "time zone is required");
        }
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException ex) {
            throw new LeaseException(LeaseException.Code.INVALID_TIME_ZONE,
                    "invalid time zone: " + timeZone);
        }
    }

    /** The member-local calendar date for a given instant. */
    public LocalDate memberLocalDate(Member member, Instant instant) {
        return instant.atZone(zoneOf(member)).toLocalDate();
    }

    /** The member-local calendar date "now". */
    public LocalDate currentPlanDate(Member member) {
        return memberLocalDate(member, now());
    }

    public int dailyLimitSeconds(AgeGroup ageGroup) {
        return switch (ageGroup) {
            case ADULT -> ADULT_DAILY_SECONDS;
            case TEEN -> TEEN_DAILY_SECONDS;
            case CHILD -> CHILD_DAILY_SECONDS;
        };
    }

    public int singleSessionSeconds(AgeGroup ageGroup) {
        return switch (ageGroup) {
            case ADULT -> ADULT_SESSION_SECONDS;
            case TEEN -> TEEN_SESSION_SECONDS;
            case CHILD -> CHILD_SESSION_SECONDS;
        };
    }

    /**
     * True when a lease granted on {@code planDate} has since crossed the member-local
     * midnight boundary and must be settled/closed against a fresh day.
     */
    public boolean hasCrossedMidnight(Member member, LocalDate planDate, Instant instant) {
        return memberLocalDate(member, instant).isAfter(planDate);
    }

    /**
     * The instant at which viewing must stop today because of the bedtime blackout
     * (bedtime minus one hour), expressed in the member's zone. Empty when the member
     * has no bedtime configured. DST is handled via {@link ZonedDateTime} resolution.
     */
    public Optional<OffsetDateTime> bedtimeDeadline(Member member, Instant instant) {
        LocalTime bedtime = member.getBedtimeLocal();
        if (bedtime == null) {
            return Optional.empty();
        }
        ZoneId zone = zoneOf(member);
        LocalDate localDate = instant.atZone(zone).toLocalDate();
        LocalTime blackoutStart = bedtime.minusHours((int) BEDTIME_BLACKOUT.toHours());
        LocalDateTime deadlineLocal = LocalDateTime.of(localDate, blackoutStart);
        // ZonedDateTime resolves gaps/overlaps from DST transitions deterministically.
        ZonedDateTime zoned = deadlineLocal.atZone(zone);
        return Optional.of(zoned.toOffsetDateTime());
    }

    /** True when the member is currently inside (at or past) the bedtime blackout window. */
    public boolean inBedtimeBlackout(Member member, Instant instant) {
        return bedtimeDeadline(member, instant)
                .map(deadline -> !instant.isBefore(deadline.toInstant()))
                .orElse(false);
    }

    /**
     * Compute the lease expiry for a newly granted or extended session: the smaller of
     * the session grant window and the remaining daily budget, further capped so it can
     * never run past the bedtime blackout window.
     *
     * @param grantSeconds seconds the caller would like to grant this session
     * @return the effective expiry instant (as OffsetDateTime, UTC-based)
     */
    public OffsetDateTime cappedExpiry(Member member, Instant from, int grantSeconds) {
        OffsetDateTime natural = from.plusSeconds(grantSeconds).atOffset(ZoneOffset.UTC);
        Optional<OffsetDateTime> bedtime = bedtimeDeadline(member, from);
        if (bedtime.isEmpty()) {
            return natural;
        }
        OffsetDateTime deadline = bedtime.get();
        return natural.toInstant().isAfter(deadline.toInstant())
                ? deadline.toInstant().atOffset(ZoneOffset.UTC)
                : natural;
    }

    /**
     * The number of seconds that may still be granted before the bedtime window,
     * capped at {@code desired}. Zero or negative means the window is closed.
     */
    public int secondsAvailableBeforeBedtime(Member member, Instant from, int desired) {
        Optional<OffsetDateTime> bedtime = bedtimeDeadline(member, from);
        if (bedtime.isEmpty()) {
            return desired;
        }
        long untilBedtime = Duration.between(from, bedtime.get().toInstant()).getSeconds();
        if (untilBedtime <= 0) {
            return 0;
        }
        return (int) Math.min(desired, untilBedtime);
    }

    /**
     * Result of evaluating a daily extension request.
     */
    public record ExtensionGrant(int grantedSeconds, OffsetDateTime expiresAt) {
    }

    /**
     * Evaluate a bedtime-safe daily extension of up to 5 minutes. The grant is clamped
     * so it never crosses the bedtime window. Throws when already used today or when the
     * bedtime window leaves no room.
     */
    public ExtensionGrant computeExtension(Member member, ViewingPlan plan, Instant from) {
        if (plan.isExtensionUsed()) {
            throw new LeaseException(LeaseException.Code.EXTENSION_ALREADY_USED,
                    "an extension has already been granted for this plan");
        }
        int allowed = secondsAvailableBeforeBedtime(member, from, MAX_EXTENSION_SECONDS);
        if (allowed <= 0) {
            throw new LeaseException(LeaseException.Code.BEDTIME_BLACKOUT,
                    "extension refused: inside the bedtime blackout window");
        }
        OffsetDateTime expiresAt = from.plusSeconds(allowed).atOffset(ZoneOffset.UTC);
        return new ExtensionGrant(allowed, expiresAt);
    }
}
