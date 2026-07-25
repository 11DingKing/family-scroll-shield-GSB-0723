package com.family.scrollshield;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A mutable {@link Clock} for deterministic tests. Time only advances when the test tells
 * it to, which lets us drive DST transitions, cross-midnight boundaries, heartbeat spacing
 * and lease expiry without real waiting.
 */
public class ControllableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public ControllableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    public ControllableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new ControllableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    public void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }
}
