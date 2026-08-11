package com.sentinel.telemetry.infrastructure.messaging;

import java.util.Objects;

import com.sentinel.telemetry.domain.InvalidTelemetryException;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;

/**
 * Translates between the wire contract and the domain, in both directions.
 *
 * <p>Hand-written on purpose. A mapping generator would earn its place across dozens of types
 * with deep nesting; here it would add a dependency and an annotation processor to replace
 * fifteen lines that a reader can check at a glance — and those fifteen lines are precisely the
 * place where a wire change must be noticed.
 *
 * <p>Stateless, so the single instance is safe on every consumer thread.
 */
public final class TelemetryMessageMapper {

    /**
     * Wire to domain.
     *
     * <p>Validation happens by construction: {@link TelemetryReadings} and {@link TelemetryEvent}
     * enforce their own invariants, so a structurally impossible payload throws
     * {@link com.sentinel.telemetry.domain.InvalidTelemetryException} here rather than entering
     * the system. Extreme-but-possible readings pass, exactly as they should.
     *
     * @throws UnsupportedSchemaVersionException if the message announces an unknown version
     */
    public TelemetryEvent toDomain(TelemetryMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        if (message.schemaVersion() != TelemetryMessage.CURRENT_SCHEMA_VERSION) {
            throw new UnsupportedSchemaVersionException(
                    message.schemaVersion(), TelemetryMessage.CURRENT_SCHEMA_VERSION);
        }

        TelemetryMessage.Readings readings = message.readings();
        if (readings == null) {
            throw new InvalidTelemetryException("readings must not be null");
        }

        return new TelemetryEvent(
                message.eventId(),
                message.machineId(),
                message.occurredAt(),
                new TelemetryReadings(
                        readings.temperatureCelsius(),
                        readings.vibrationMillimetresPerSecond(),
                        readings.pressureBar(),
                        readings.powerConsumptionKilowatts(),
                        readings.rotationSpeedRpm()));
    }

    /** Domain to wire, always stamping the version this build speaks. */
    public TelemetryMessage toMessage(TelemetryEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        TelemetryReadings readings = event.readings();

        return new TelemetryMessage(
                TelemetryMessage.CURRENT_SCHEMA_VERSION,
                event.eventId(),
                event.machineId(),
                event.occurredAt(),
                new TelemetryMessage.Readings(
                        readings.temperatureCelsius(),
                        readings.vibrationMillimetresPerSecond(),
                        readings.pressureBar(),
                        readings.powerConsumptionKilowatts(),
                        readings.rotationSpeedRpm()));
    }
}
