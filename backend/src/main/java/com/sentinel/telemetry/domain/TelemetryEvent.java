package com.sentinel.telemetry.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One measurement sample reported by a machine — the central event of the platform.
 *
 * <p>Immutable by construction: an event describes something that already happened, so there is
 * no sane meaning for mutating one. This also makes it safe to hand the same instance to several
 * rules, or to concurrent consumer threads, without defensive copying.
 *
 * <p>{@code eventId} is supplied by the producer, not generated here. It is the key that makes
 * duplicate delivery detectable: Kafka guarantees at-least-once, so the same event will
 * occasionally be processed twice, and only a producer-assigned identifier stays stable across
 * those redeliveries. Generating it on arrival would give a redelivered event a fresh identity
 * and defeat idempotency (M5).
 *
 * <p>{@code occurredAt} is the instant the machine took the sample, which is not the instant
 * Sentinel received it. Keeping the distinction visible matters once ordering, lateness and
 * clock skew are discussed.
 */
public record TelemetryEvent(
        UUID eventId,
        UUID machineId,
        Instant occurredAt,
        TelemetryReadings readings) {

    public TelemetryEvent {
        requirePresent(eventId, "eventId");
        requirePresent(machineId, "machineId");
        requirePresent(occurredAt, "occurredAt");
        requirePresent(readings, "readings");
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new InvalidTelemetryException(field + " must not be null");
        }
    }
}
