package com.sentinel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the full application context.
 *
 * <p>Its value grows with the project: as starters, configuration properties and beans
 * accumulate, this is the test that catches a broken bean graph or an unresolvable
 * placeholder before anything is deployed. It intentionally requires no running
 * infrastructure, so the build stays fast and offline-friendly.
 */
@SpringBootTest
class SentinelApplicationTests {

    @Test
    void contextLoads() {
        // The assertion is the successful context startup itself: a failure here
        // surfaces as an exception before the test body is ever reached.
    }
}
