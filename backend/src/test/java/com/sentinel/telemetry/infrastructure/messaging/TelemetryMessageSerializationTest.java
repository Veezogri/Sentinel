package com.sentinel.telemetry.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Pins the bytes on the wire.
 *
 * <p>The point of a versioned contract is that it does not drift silently. These assertions fail
 * if a field is renamed, if an instant stops being ISO-8601, or if the version disappears — all
 * changes that would compile perfectly and break every consumer.
 */
class TelemetryMessageSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static TelemetryMessage message() {
        return new TelemetryMessage(
                1,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Instant.parse("2026-01-15T10:00:00Z"),
                new TelemetryMessage.Readings(62.5, 2.4, 5.9, 35.1, 1450.0));
    }

    @Test
    void shouldSerialiseToTheDocumentedShape() throws Exception {
        String json = objectMapper.writeValueAsString(message());

        assertThat(json).isEqualTo("{"
                + "\"schemaVersion\":1,"
                + "\"eventId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"machineId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"occurredAt\":\"2026-01-15T10:00:00Z\","
                + "\"readings\":{"
                + "\"temperatureCelsius\":62.5,"
                + "\"vibrationMillimetresPerSecond\":2.4,"
                + "\"pressureBar\":5.9,"
                + "\"powerConsumptionKilowatts\":35.1,"
                + "\"rotationSpeedRpm\":1450.0}}");
    }

    @Test
    void shouldDeserialiseWhatItSerialises() throws Exception {
        String json = objectMapper.writeValueAsString(message());

        assertThat(objectMapper.readValue(json, TelemetryMessage.class)).isEqualTo(message());
    }

    /** An instant must travel as a readable timestamp, not as a bag of numeric fields. */
    @Test
    void shouldWriteInstantsAsIso8601() throws Exception {
        assertThat(objectMapper.writeValueAsString(message())).contains("\"2026-01-15T10:00:00Z\"");
    }
}
