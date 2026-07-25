package com.family.scrollshield;

import com.family.scrollshield.domain.AgeGroup;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.domain.PlanStatus;
import com.family.scrollshield.domain.ViewingPlan;
import com.family.scrollshield.service.LeaseException;
import com.family.scrollshield.service.QuotaPolicyService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Pure unit tests for the quota policy engine: per-age limits, single-session caps,
 * timezone validation, DST fall-back handling, cross-midnight detection and the bedtime
 * blackout that also caps extensions. No database or Redis required.
 */
class QuotaPolicyServiceTest {

    private QuotaPolicyService policyAt(Instant instant) {
        return new QuotaPolicyService(java.time.Clock.fixed(instant, ZoneId.of("UTC")));
    }

    private Member member(AgeGroup ageGroup, String zone, LocalTime bedtime) {
        return Member.builder()
                .id(UUID.randomUUID())
                .externalId("m")
                .displayName("m")
                .ageGroup(ageGroup)
                .timeZone(zone)
                .bedtimeLocal(bedtime)
                .build();
    }

    @Test
    void adultDailyLimitIs60Minutes() {
        assertThat(policyAt(Instant.now()).dailyLimitSeconds(AgeGroup.ADULT)).isEqualTo(60 * 60);
    }

    @Test
    void teenDailyLimitIs30Minutes() {
        assertThat(policyAt(Instant.now()).dailyLimitSeconds(AgeGroup.TEEN)).isEqualTo(30 * 60);
    }

    @Test
    void adultSingleSessionIs15Minutes() {
        assertThat(policyAt(Instant.now()).singleSessionSeconds(AgeGroup.ADULT)).isEqualTo(15 * 60);
    }

    @Test
    void childSingleSessionIs10Minutes() {
        assertThat(policyAt(Instant.now()).singleSessionSeconds(AgeGroup.CHILD)).isEqualTo(10 * 60);
    }

    @Test
    void invalidTimeZoneThrows() {
        QuotaPolicyService policy = policyAt(Instant.now());
        assertThatThrownBy(() -> policy.zoneOf("Not/AZone"))
                .isInstanceOf(LeaseException.class)
                .satisfies(ex -> assertThat(((LeaseException) ex).getCode())
                        .isEqualTo(LeaseException.Code.INVALID_TIME_ZONE));
    }

    @Test
    void crossMidnightDetection() {
        ZoneId ny = ZoneId.of("America/New_York");
        // 23:30 local on Jan 15 in New York.
        Instant grantInstant = ZonedDateTime.of(2026, 1, 15, 23, 30, 0, 0, ny).toInstant();
        QuotaPolicyService policy = policyAt(grantInstant);
        Member m = member(AgeGroup.ADULT, "America/New_York", null);
        LocalDate planDate = policy.memberLocalDate(m, grantInstant);
        assertThat(planDate).isEqualTo(LocalDate.of(2026, 1, 15));

        // 40 minutes later it is 00:10 local on Jan 16 -> crossed midnight.
        Instant later = grantInstant.plusSeconds(40 * 60);
        assertThat(policy.hasCrossedMidnight(m, planDate, later)).isTrue();
        // 10 minutes after grant it is still the 15th.
        assertThat(policy.hasCrossedMidnight(m, planDate, grantInstant.plusSeconds(600))).isFalse();
    }

    @Test
    void daylightSavingTimeFallBack() {
        // US DST "fall back" 2026: clocks go from 02:00 back to 01:00 on Nov 1.
        ZoneId ny = ZoneId.of("America/New_York");
        Member teen = member(AgeGroup.TEEN, "America/New_York", LocalTime.of(21, 0));

        // Just before the transition, at 00:30 local on Nov 1 the member-local date is Nov 1.
        Instant beforeTransition = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, ny).toInstant();
        QuotaPolicyService policy = policyAt(beforeTransition);
        assertThat(policy.memberLocalDate(teen, beforeTransition)).isEqualTo(LocalDate.of(2026, 11, 1));

        // Bedtime deadline (bedtime 21:00 minus 1h = 20:00 local) resolves to a valid instant
        // on the DST day and is one hour before the local bedtime.
        OffsetDateTime deadline = policy.bedtimeDeadline(teen, beforeTransition).orElseThrow();
        ZonedDateTime deadlineLocal = deadline.toInstant().atZone(ny);
        assertThat(deadlineLocal.toLocalTime()).isEqualTo(LocalTime.of(20, 0));
        assertThat(deadlineLocal.toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void extensionBlockedByBedtimeWindow() {
        ZoneId ny = ZoneId.of("America/New_York");
        Member teen = member(AgeGroup.TEEN, "America/New_York", LocalTime.of(21, 0));
        // 20:30 local: already inside the 20:00-21:00 blackout window.
        Instant inside = ZonedDateTime.of(2026, 1, 15, 20, 30, 0, 0, ny).toInstant();
        QuotaPolicyService policy = policyAt(inside);
        ViewingPlan plan = openPlan(teen);

        LeaseException ex = catchThrowableOfType(
                () -> policy.computeExtension(teen, plan, inside), LeaseException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(LeaseException.Code.BEDTIME_BLACKOUT);
    }

    @Test
    void proposedExpiryCappedByBedtimeBlackout() {
        ZoneId ny = ZoneId.of("America/New_York");
        Member teen = member(AgeGroup.TEEN, "America/New_York", LocalTime.of(21, 0));
        // 19:50 local: 10 minutes before the 20:00 blackout start.
        Instant from = ZonedDateTime.of(2026, 1, 15, 19, 50, 0, 0, ny).toInstant();
        QuotaPolicyService policy = policyAt(from);

        // Asking for a 15-minute session must be capped to 10 minutes (bedtime window).
        OffsetDateTime expiry = policy.cappedExpiry(teen, from, 15 * 60);
        long seconds = java.time.Duration.between(from, expiry.toInstant()).getSeconds();
        assertThat(seconds).isEqualTo(10 * 60);

        // And an extension of up to 5 minutes is fully available before the window.
        QuotaPolicyService.ExtensionGrant grant = policy.computeExtension(teen, openPlan(teen), from);
        assertThat(grant.grantedSeconds()).isEqualTo(5 * 60);
    }

    @Test
    void bedtimeAfterMidnightBlocksLateEveningAndEarlyMorning() {
        ZoneId ny = ZoneId.of("America/New_York");
        // Bedtime 00:30 -> blackout window is [23:30 (prev day), 00:30).
        Member teen = member(AgeGroup.TEEN, "America/New_York", LocalTime.of(0, 30));

        // 23:45 on Jan 15 (local) is inside the window -> blocked.
        Instant lateEvening = ZonedDateTime.of(2026, 1, 15, 23, 45, 0, 0, ny).toInstant();
        assertThat(policyAt(lateEvening).inBedtimeBlackout(teen, lateEvening)).isTrue();

        // 00:15 on Jan 16 (local) is still inside the window (before 00:30) -> blocked.
        Instant earlyMorning = ZonedDateTime.of(2026, 1, 16, 0, 15, 0, 0, ny).toInstant();
        assertThat(policyAt(earlyMorning).inBedtimeBlackout(teen, earlyMorning)).isTrue();

        // 22:00 on Jan 15 (local), well before 23:30 -> allowed.
        Instant earlyEvening = ZonedDateTime.of(2026, 1, 15, 22, 0, 0, 0, ny).toInstant();
        assertThat(policyAt(earlyEvening).inBedtimeBlackout(teen, earlyEvening)).isFalse();

        // 01:00 on Jan 16 (local), just after bedtime -> allowed again.
        Instant afterBed = ZonedDateTime.of(2026, 1, 16, 1, 0, 0, 0, ny).toInstant();
        assertThat(policyAt(afterBed).inBedtimeBlackout(teen, afterBed)).isFalse();
    }

    @Test
    void bedtimeAfterMidnightDeadlineIsExactlyOneHourBeforeNextBedtime() {
        ZoneId ny = ZoneId.of("America/New_York");
        Member teen = member(AgeGroup.TEEN, "America/New_York", LocalTime.of(0, 30));

        // At 22:00 on Jan 15, the next bedtime is 00:30 on Jan 16; deadline = 23:30 Jan 15.
        Instant at22 = ZonedDateTime.of(2026, 1, 15, 22, 0, 0, 0, ny).toInstant();
        OffsetDateTime deadline = policyAt(at22).bedtimeDeadline(teen, at22).orElseThrow();
        ZonedDateTime local = deadline.toInstant().atZone(ny);
        assertThat(local.toLocalTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(local.toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void bedtimeBlackoutIsOneRealHourAcrossDstFallBack() {
        // On the DST fall-back night (Nov 1, 2026) the wall-clock hour 01:00-02:00 repeats.
        // A bedtime of 01:45 must still yield a blackout that begins exactly one *real* hour
        // (3600 seconds) before the bedtime instant, not one wall-clock hour.
        ZoneId ny = ZoneId.of("America/New_York");
        Member teen = member(AgeGroup.TEEN, "America/New_York", LocalTime.of(1, 45));

        Instant from = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ny).toInstant();
        QuotaPolicyService policy = policyAt(from);
        OffsetDateTime deadline = policy.bedtimeDeadline(teen, from).orElseThrow();

        // Bedtime instant the policy targeted is the next 01:45 strictly after `from`.
        Instant bedtimeInstant = ZonedDateTime.of(2026, 11, 1, 1, 45, 0, 0, ny).toInstant();
        long gap = java.time.Duration.between(deadline.toInstant(), bedtimeInstant).getSeconds();
        assertThat(gap).isEqualTo(3600);

        // Exactly at the deadline no session time remains before the blackout.
        assertThat(policyAt(deadline.toInstant()).secondsAvailableBeforeBedtime(
                teen, deadline.toInstant(), 5 * 60)).isZero();
    }

    private ViewingPlan openPlan(Member m) {
        return ViewingPlan.builder()
                .id(UUID.randomUUID())
                .memberId(m.getId())
                .planDate(LocalDate.of(2026, 1, 15))
                .dailyLimitSeconds(30 * 60)
                .consumedSeconds(0)
                .extensionUsed(false)
                .extensionSeconds(0)
                .status(PlanStatus.OPEN)
                .build();
    }
}
