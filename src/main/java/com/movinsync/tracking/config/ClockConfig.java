package com.movinsync.tracking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides an injectable Clock bean. All time-dependent logic must use this
 * instead of calling Instant.now() directly, enabling deterministic tests.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
