package com.sentinel.telemetry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sentinel.testsupport.DomainFixtures;

class TelemetryEventTest {

    private static final UUID EVENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-15T10:00:00Z");

    @Test
    void shouldCreateEventWithAllFields() {
        TelemetryReadings readings = DomainFixtures.nominalReadings();

        TelemetryEvent event = new TelemetryEvent(EVENT_ID, DomainFixtures.MACHINE_ID, OCCURRED_AT, readings);

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.machineId()).isEqualTo(DomainFixtures.MACHINE_ID);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.readings()).isEqualTo(readings);
    }

    @Test
    void shouldRejectMissingEventId() {
        assertThatThrownBy(() -> new TelemetryEvent(
                null, DomainFixtures.MACHINE_ID, OCCURRED_AT, DomainFixtures.nominalReadings()))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void shouldRejectMissingMachineId() {
        assertThatThrownBy(() -> new TelemetryEvent(
                EVENT_ID, null, OCCURRED_AT, DomainFixtures.nominalReadings()))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("machineId");
    }

    @Test
    void shouldRejectMissingTimestamp() {
        assertThatThrownBy(() -> new TelemetryEvent(
                EVENT_ID, DomainFixtures.MACHINE_ID, null, DomainFixtures.nominalReadings()))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    void shouldRejectMissingReadings() {
        assertThatThrownBy(() -> new TelemetryEvent(EVENT_ID, DomainFixtures.MACHINE_ID, OCCURRED_AT, null))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("readings");
    }

    /**
     * Two deliveries of the same event must be indistinguishable, otherwise duplicate detection
     * cannot be based on equality of what was received.
     */
    @Test
    void shouldTreatTwoEventsWithSameContentAsEqual() {
        TelemetryEvent first = new TelemetryEvent(
                EVENT_ID, DomainFixtures.MACHINE_ID, OCCURRED_AT, DomainFixtures.nominalReadings());
        TelemetryEvent redelivered = new TelemetryEvent(
                EVENT_ID, DomainFixtures.MACHINE_ID, OCCURRED_AT, DomainFixtures.nominalReadings());

        assertThat(redelivered).isEqualTo(first).hasSameHashCodeAs(first);
    }
}
