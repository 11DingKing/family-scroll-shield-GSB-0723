package com.family.scrollshield;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public class ControllableClock extends Clock {

    private volatile Clock delegate = Clock.systemUTC();

    public void setFixed(Instant at) {
        this.delegate = Clock.fixed(at, ZoneOffset.UTC);
    }

    public void setFixed(Instant at, ZoneId zone) {
        this.delegate = Clock.fixed(at, zone);
    }

    public void setSystem() {
        this.delegate = Clock.systemUTC();
    }

    public void advance(Duration d) {
        Instant now = delegate.instant();
        this.delegate = Clock.fixed(now.plus(d), delegate.getZone());
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.withZone(zone);
    }

    @Override
    public Instant instant() {
        return delegate.instant();
    }
}
