package com.family.scrollshield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.family.scrollshield.domain.AgeGroup;
import com.family.scrollshield.domain.Member;
import com.family.scrollshield.service.LeaseException;
import com.family.scrollshield.service.QuotaPolicyService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class QuotaPolicyServiceTest {

    private final QuotaPolicyService policy = new QuotaPolicyService(Clock.systemUTC());

    @Test
    void adultDailyLimitIs60Minutes() {
        assertThat(policy.dailyLimitSeconds(AgeGroup.ADULT)).isEqualTo(60 * 60);
    }

    @Test
    void teenDailyLimitIs30Minutes() {
        assertThat(policy.dailyLimitSeconds(AgeGroup.TEEN)).isEqualTo(30 * 60);
    }

    @Test
    void childSingleSessionIs10Minutes() {
        assertThat(policy.sessionLimitSeconds(AgeGroup.CHILD)).isEqualTo(10 * 60);
    }

    @Test
    void adultSingleSessionIs15Minutes() {
        assertThat(policy.sessionLimitSeconds(AgeGroup.ADULT)).isEqualTo(15 * 60);
    }

    @Test
    void localDateAcrossTimeZones() {
        Member utc = Member.builder().timeZone("UTC").build();
        Member sh = Member.builder().timeZone("Asia/Shanghai").build();

        Instant i = Instant.parse("2026-07-24T16:30:00Z");
        assertThat(policy.localDateFor(utc, i)).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(policy.localDateFor(sh, i)).isEqualTo(LocalDate.of(2026, 7, 25));
    }

    @Test
    void daylightSavingTimeSpringForward() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant beforeSpring = ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, newYork).toInstant();
        Instant afterSpring = ZonedDateTime.of(2026, 3, 8, 5, 30, 0, 0, newYork).toInstant();

        Member m = Member.builder().timeZone("America/New_York").build();
        LocalDate d1 = policy.localDateFor(m, beforeSpring);
        LocalDate d2 = policy.localDateFor(m, afterSpring);
        assertThat(d1).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(d2).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(beforeSpring).isBefore(afterSpring);
    }

    @Test
    void daylightSavingTimeFallBack() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant beforeFall = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, newYork).toInstant();
        Instant afterFall = ZonedDateTime.of(2026, 11, 1, 3, 30, 0, 0, newYork).toInstant();

        Member m = Member.builder().timeZone("America/New_York").build();
        LocalDate d1 = policy.localDateFor(m, beforeFall);
        LocalDate d2 = policy.localDateFor(m, afterFall);
        assertThat(d1).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(d2).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void bedtimeBlackoutForTeenOneHourBefore() {
        Member teen = Member.builder()
                .ageGroup(AgeGroup.TEEN)
                .timeZone("Asia/Shanghai")
                .bedtimeLocal(LocalTime.of(22, 0))
                .build();

        Instant before = ZonedDateTime.of(2026, 7, 24, 20, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
        Instant at2030 = ZonedDateTime.of(2026, 7, 24, 20, 30, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
        Instant inBlackout = ZonedDateTime.of(2026, 7, 24, 21, 30, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
        Instant afterBed = ZonedDateTime.of(2026, 7, 24, 22, 30, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();

        assertThat(policy.isWithinBedtimeBlackout(teen, before)).isFalse();
        assertThat(policy.isWithinBedtimeBlackout(teen, at2030)).isFalse();
        assertThat(policy.isWithinBedtimeBlackout(teen, inBlackout)).isTrue();
        assertThat(policy.isWithinBedtimeBlackout(teen, afterBed)).isFalse();
    }

    @Test
    void proposedExpiryCappedByBedtimeBlackout() {
        Member teen = Member.builder()
                .ageGroup(AgeGroup.TEEN)
                .timeZone("Asia/Shanghai")
                .bedtimeLocal(LocalTime.of(22, 0))
                .build();

        Instant at2055 = ZonedDateTime.of(2026, 7, 24, 20, 55, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
        Instant expiry = policy.proposedLeaseExpiry(teen, at2055, 30 * 60);
        ZonedDateTime expiryZ = expiry.atZone(ZoneId.of("Asia/Shanghai"));
        assertThat(expiryZ.toLocalTime()).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    void extensionBlockedByBedtimeWindow() {
        Member teen = Member.builder()
                .ageGroup(AgeGroup.TEEN)
                .timeZone("Asia/Shanghai")
                .bedtimeLocal(LocalTime.of(22, 0))
                .build();

        Instant at2058 = ZonedDateTime.of(2026, 7, 24, 20, 58, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
        assertThatThrownBy(() -> policy.clampExtensionToBedtime(teen, at2058, 5 * 60))
                .isInstanceOf(LeaseException.class)
                .hasMessageContaining("睡前");
    }

    @Test
    void crossMidnightDetection() {
        Member sh = Member.builder().timeZone("Asia/Shanghai").build();
        Instant before = ZonedDateTime.of(2026, 7, 24, 23, 59, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
        Instant after = ZonedDateTime.of(2026, 7, 25, 0, 1, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
        assertThat(policy.crossesMidnight(sh, before, after)).isTrue();
    }

    @Test
    void invalidTimeZoneThrows() {
        assertThatThrownBy(() -> policy.resolveZone("Not/AZone"))
                .isInstanceOf(LeaseException.class);
    }
}
