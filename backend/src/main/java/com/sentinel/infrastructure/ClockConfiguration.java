package com.sentinel.infrastructure;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the system clock an injected dependency rather than a static call.
 *
 * <p>The domain already refuses to read the clock itself — every instant is a parameter. This
 * extends the same rule to the application layer, so a processor can be handed a fixed clock in a
 * test instead of the test having to tolerate whatever {@code Instant.now()} returns.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
