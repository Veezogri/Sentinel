package com.sentinel.system;

import java.time.Instant;

/**
 * Payload of {@code GET /api/v1/system/health}.
 *
 * @param status      always {@code UP} — see {@link SystemHealthController} for why
 * @param application the configured application name
 * @param version     the build version, sourced from the Maven project version
 * @param timestamp   server-side instant the response was produced, in UTC
 */
public record SystemHealthResponse(
        String status,
        String application,
        String version,
        Instant timestamp) {
}
