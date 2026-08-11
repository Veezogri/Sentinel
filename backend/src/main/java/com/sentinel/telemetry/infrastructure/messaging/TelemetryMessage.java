package com.sentinel.telemetry.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * The JSON contract carried on {@code sentinel.telemetry.raw} — version 1.
 *
 * <h2>Why this is not {@link com.sentinel.telemetry.domain.TelemetryEvent}</h2>
 * Jackson could serialise the domain record directly, and that is exactly the trap. Publishing a
 * domain type makes every internal refactor a wire-breaking change: renaming a field, splitting a
 * value object or tightening a type would silently change the bytes that other systems — and
 * every message already sitting in a topic or a dead-letter queue — depend on. The two evolve for
 * different reasons and at different speeds, so they are different types with an explicit mapping
 * between them.
 *
 * <p>The separation also keeps Jackson at the edge. The domain has no serialisation annotations
 * and no {@code com.fasterxml} import, which is asserted by a test rather than left to
 * discipline.
 *
 * <h2>Versioning</h2>
 * {@code schemaVersion} is present from the first message rather than added once it hurts. A
 * consumer that meets version 2 can then reject or adapt it deliberately, instead of guessing
 * from the shape of the payload. This is a version field, not a schema registry: Avro or Protobuf
 * with a registry would earn their place once contracts cross team boundaries or payload size
 * matters, neither of which is true here.
 *
 * <h2>Format</h2>
 * JSON, because being able to read a message straight out of a topic during development is worth
 * more right now than the bytes a binary format would save.
 */
public record TelemetryMessage(
        int schemaVersion,
        UUID eventId,
        UUID machineId,
        Instant occurredAt,
        Readings readings) {

    /** The only version emitted today; consumers reject anything else. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Measurements as they travel.
     *
     * <p>Field names carry their unit, so a consumer written in another language cannot silently
     * assume psi where the producer meant bar.
     */
    public record Readings(
            double temperatureCelsius,
            double vibrationMillimetresPerSecond,
            double pressureBar,
            double powerConsumptionKilowatts,
            double rotationSpeedRpm) {
    }
}
