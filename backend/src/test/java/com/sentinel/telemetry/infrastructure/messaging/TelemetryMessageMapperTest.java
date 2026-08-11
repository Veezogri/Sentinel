package com.sentinel.telemetry.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sentinel.telemetry.domain.InvalidTelemetryException;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;

class TelemetryMessageMapperTest {

    private static final UUID EVENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MACHINE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-15T10:00:00Z");

    private final TelemetryMessageMapper mapper = new TelemetryMessageMapper();

    private static TelemetryEvent event() {
        return new TelemetryEvent(EVENT_ID, MACHINE_ID, OCCURRED_AT,
                new TelemetryReadings(62.5, 2.4, 5.9, 35.1, 1450.0));
    }

    private static TelemetryMessage message(int schemaVersion) {
        return new TelemetryMessage(schemaVersion, EVENT_ID, MACHINE_ID, OCCURRED_AT,
                new TelemetryMessage.Readings(62.5, 2.4, 5.9, 35.1, 1450.0));
    }

    @Test
    void shouldMapDomainToWireStampingTheCurrentSchemaVersion() {
        TelemetryMessage message = mapper.toMessage(event());

        assertThat(message.schemaVersion()).isEqualTo(TelemetryMessage.CURRENT_SCHEMA_VERSION);
        assertThat(message.eventId()).isEqualTo(EVENT_ID);
        assertThat(message.machineId()).isEqualTo(MACHINE_ID);
        assertThat(message.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(message.readings().temperatureCelsius()).isEqualTo(62.5);
        assertThat(message.readings().rotationSpeedRpm()).isEqualTo(1450.0);
    }

    @Test
    void shouldMapWireToDomain() {
        TelemetryEvent mapped = mapper.toDomain(message(1));

        assertThat(mapped).isEqualTo(event());
    }

    /** The pair must be lossless, or a republished event would not be the event received. */
    @Test
    void shouldRoundTripWithoutLosingAnything() {
        assertThat(mapper.toDomain(mapper.toMessage(event()))).isEqualTo(event());
    }

    @Test
    void shouldRejectAnUnknownSchemaVersion() {
        assertThatThrownBy(() -> mapper.toDomain(message(2)))
                .isInstanceOf(UnsupportedSchemaVersionException.class)
                .hasMessageContaining("version 2");
    }

    @Test
    void shouldRejectAMessageWithoutReadings() {
        TelemetryMessage withoutReadings =
                new TelemetryMessage(1, EVENT_ID, MACHINE_ID, OCCURRED_AT, null);

        assertThatThrownBy(() -> mapper.toDomain(withoutReadings))
                .isInstanceOf(InvalidTelemetryException.class);
    }

    /** Domain invariants apply at the boundary: a corrupt payload never becomes a domain object. */
    @Test
    void shouldRejectStructurallyImpossibleReadings() {
        TelemetryMessage negativeRpm = new TelemetryMessage(1, EVENT_ID, MACHINE_ID, OCCURRED_AT,
                new TelemetryMessage.Readings(62.5, 2.4, 5.9, 35.1, -1.0));

        assertThatThrownBy(() -> mapper.toDomain(negativeRpm))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("rotationSpeedRpm");
    }

    @Test
    void shouldRejectAMessageMissingItsIdentifiers() {
        TelemetryMessage noEventId = new TelemetryMessage(1, null, MACHINE_ID, OCCURRED_AT,
                new TelemetryMessage.Readings(62.5, 2.4, 5.9, 35.1, 1450.0));

        assertThatThrownBy(() -> mapper.toDomain(noEventId))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("eventId");
    }

    /** An alarming but valid reading must survive the boundary; it is the payload that matters. */
    @Test
    void shouldCarryExtremeButValidReadings() {
        TelemetryMessage overheating = new TelemetryMessage(1, EVENT_ID, MACHINE_ID, OCCURRED_AT,
                new TelemetryMessage.Readings(140.0, 2.4, 5.9, 35.1, 1450.0));

        assertThat(mapper.toDomain(overheating).readings().temperatureCelsius()).isEqualTo(140.0);
    }
}
