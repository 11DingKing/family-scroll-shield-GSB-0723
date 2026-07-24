package com.family.scrollshield.service;

import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;

@Service
public class TimezoneService {

    public ZoneId getZoneId(String timezone) {
        return ZoneId.of(timezone != null ? timezone : "UTC");
    }

    public LocalDate getLocalDate(Instant utcInstant, ZoneId zoneId) {
        return utcInstant.atZone(zoneId).toLocalDate();
    }

    public LocalDate getLocalDate(Instant utcInstant, String timezone) {
        return getLocalDate(utcInstant, getZoneId(timezone));
    }

    public Instant getDayStart(LocalDate date, ZoneId zoneId) {
        return date.atStartOfDay(zoneId).toInstant();
    }

    public Instant getDayEnd(LocalDate date, ZoneId zoneId) {
        return date.plusDays(1).atStartOfDay(zoneId).toInstant();
    }

    public boolean isWithinBedtimeWindow(Instant now, LocalTime bedtime, ZoneId zoneId, int beforeMinutes) {
        if (bedtime == null) {
            return false;
        }
        ZonedDateTime zonedNow = now.atZone(zoneId);
        LocalDate today = zonedNow.toLocalDate();

        ZonedDateTime bedtimeToday = bedtime.atDate(today).atZone(zoneId);
        ZonedDateTime windowStart = bedtimeToday.minusMinutes(beforeMinutes);

        if (zonedNow.isAfter(windowStart) && !zonedNow.isAfter(bedtimeToday)) {
            return true;
        }

        ZonedDateTime bedtimeTomorrow = bedtime.atDate(today.plusDays(1)).atZone(zoneId);
        ZonedDateTime midnight = today.plusDays(1).atStartOfDay(zoneId);
        if (bedtimeToday.isBefore(midnight) || bedtimeToday.equals(midnight)) {
            if (zonedNow.isAfter(windowStart) && zonedNow.isBefore(midnight)) {
                return true;
            }
        }

        return false;
    }

    public boolean isWithinBedtimeWindow(Instant now, LocalTime bedtime, String timezone, int beforeMinutes) {
        return isWithinBedtimeWindow(now, bedtime, getZoneId(timezone), beforeMinutes);
    }

    public boolean isAfterBedtime(Instant now, LocalTime bedtime, ZoneId zoneId) {
        if (bedtime == null) {
            return false;
        }
        ZonedDateTime zonedNow = now.atZone(zoneId);
        LocalDate today = zonedNow.toLocalDate();
        ZonedDateTime bedtimeToday = bedtime.atDate(today).atZone(zoneId);
        ZonedDateTime dayStart = today.atStartOfDay(zoneId);

        if (zonedNow.isBefore(dayStart)) {
            return true;
        }
        return zonedNow.isAfter(bedtimeToday);
    }

    public long minutesBetween(Instant start, Instant end) {
        return ChronoUnit.MINUTES.between(start, end);
    }

    public long secondsBetween(Instant start, Instant end) {
        return ChronoUnit.SECONDS.between(start, end);
    }
}
