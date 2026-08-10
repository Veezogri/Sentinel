package com.sentinel.system;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness endpoint of the platform.
 *
 * <p>This deliberately coexists with Spring Boot Actuator rather than replacing it.
 * The two answer different questions and have different audiences:
 *
 * <ul>
 *   <li>{@code /actuator/health} is an <em>operational readiness</em> surface: it aggregates
 *       dependency health (database, broker, cache) and is consumed by orchestrators and
 *       monitoring. It becomes an authenticated, internal-only surface once security lands.</li>
 *   <li>{@code /api/v1/system/health} is a <em>liveness</em> answer on the versioned public API:
 *       "the backend is reachable and this is the build you are talking to". It is what the
 *       Angular shell calls to render a connection indicator, so it must stay stable, cheap,
 *       and free of dependency checks — a degraded database must not make it fail.</li>
 * </ul>
 *
 * <p>Because it performs no dependency probing, {@code status} is a constant: the endpoint
 * answering at all <em>is</em> the signal.
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemHealthController {

    private static final String STATUS_UP = "UP";

    private final String applicationName;
    private final String applicationVersion;

    public SystemHealthController(
            @Value("${spring.application.name}") String applicationName,
            @Value("${spring.application.version}") String applicationVersion) {
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
    }

    @GetMapping("/health")
    public SystemHealthResponse health() {
        return new SystemHealthResponse(STATUS_UP, applicationName, applicationVersion, Instant.now());
    }
}
