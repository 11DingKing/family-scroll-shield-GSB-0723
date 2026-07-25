package com.family.scrollshield;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * Installs the shared {@link AbstractIntegrationTest#CLOCK} as the primary {@link Clock}
 * bean so all time-dependent policy in the running context advances only when a test
 * drives it. Imported explicitly by each integration test.
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public Clock testClock() {
        return AbstractIntegrationTest.CLOCK;
    }
}
