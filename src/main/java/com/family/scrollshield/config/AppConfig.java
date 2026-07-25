package com.family.scrollshield.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Application-wide beans. A single injectable {@link Clock} makes all time-dependent
 * policy deterministically testable (tests substitute a controllable clock), which is
 * essential for verifying DST, cross-midnight and bedtime behavior.
 *
 * <p>The {@code ObjectMapper} is provided by Spring Boot's Jackson auto-configuration,
 * which already registers the JSR-310 (java.time) module and disables timestamp dates.
 */
@Configuration
public class AppConfig {

    /**
     * System UTC clock in production. Tests override this bean with a controllable clock.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
