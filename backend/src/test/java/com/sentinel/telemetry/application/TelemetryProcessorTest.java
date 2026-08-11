package com.sentinel.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sentinel.alert.domain.AlertSeverity;
import com.sentinel.alert.domain.AlertType;
import com.sentinel.machine.application.InMemoryMachineRegistry;
import com.sentinel.machine.application.InMemoryMachineStateStore;
import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.machine.application.MachineStateStore;
import com.sentinel.machine.domain.HealthStatus;
import com.sentinel.machine.domain.Machine;
import com.sentinel.machine.domain.MachineType;
import com.sentinel.rule.domain.RuleEngine;
import com.sentinel.rule.domain.RuleResult;
import com.sentinel.rule.domain.rules.AbnormalPressureRule;
import com.sentinel.rule.domain.rules.ExcessiveVibrationRule;
import com.sentinel.rule.domain.rules.HighTemperatureRule;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;

/**
 * The processor is exercised with real collaborators rather than mocks: the store and registry are
 * the production in-memory implementations and the rule engine is the real one. Mocking them would
 * assert that the processor calls certain methods, which is not the behaviour worth protecting.
 */
class TelemetryProcessorTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant PROCESSED_AT = Instant.parse("2026-01-15T10:00:00.040Z");
    private static final UUID MACHINE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private MachineStateStore stateStore;
    private MachineRegistry registry;
    private TelemetryProcessor processor;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryMachineStateStore();
        registry = new InMemoryMachineRegistry();
        registry.register(Machine.register(MACHINE_ID, "PUMP-001", MachineType.PUMP, T0));

        processor = new TelemetryProcessor(
                stateStore,
                registry,
                new RuleEngine(List.of(
                        HighTemperatureRule.withDefaults(),
                        ExcessiveVibrationRule.withDefaults(),
                        AbnormalPressureRule.withDefaults())),
                Clock.fixed(PROCESSED_AT, ZoneOffset.UTC));
    }

    private static TelemetryEvent event(Instant occurredAt, double temperature) {
        return new TelemetryEvent(UUID.randomUUID(), MACHINE_ID, occurredAt,
                new TelemetryReadings(temperature, 2.0, 5.0, 30.0, 1400.0));
    }

    @Test
    void shouldUpdateStateAndReportNoFindingsForNominalTelemetry() {
        TelemetryProcessingResult result = processor.process(event(T0, 62.0));

        assertThat(result.findings()).isEmpty();
        assertThat(result.stateAdvanced()).isTrue();
        assertThat(result.state().healthStatus()).isEqualTo(HealthStatus.NORMAL);
        assertThat(result.state().latestReadings().temperatureCelsius()).isEqualTo(62.0);
    }

    @Test
    void shouldRecordProcessingInstantSeparatelyFromEventInstant() {
        TelemetryProcessingResult result = processor.process(event(T0, 62.0));

        assertThat(result.state().lastTelemetryAt()).isEqualTo(T0);
        assertThat(result.state().lastUpdatedAt()).isEqualTo(PROCESSED_AT);
    }

    @Test
    void shouldProduceACriticalFindingAndCriticalHealthForAnOverheat() {
        TelemetryProcessingResult result = processor.process(event(T0, 99.0));

        assertThat(result.hasFindings()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.alertType()).isEqualTo(AlertType.HIGH_TEMPERATURE);
            assertThat(finding.severity()).isEqualTo(AlertSeverity.CRITICAL);
        });
        assertThat(result.state().healthStatus()).isEqualTo(HealthStatus.CRITICAL);
    }

    @Test
    void shouldReportSeveralFindingsFromOneEvent() {
        TelemetryEvent broken = new TelemetryEvent(UUID.randomUUID(), MACHINE_ID, T0,
                new TelemetryReadings(99.0, 20.0, 0.2, 30.0, 1400.0));

        assertThat(processor.process(broken).findings())
                .extracting(RuleResult.Triggered::alertType)
                .containsExactly(
                        AlertType.HIGH_TEMPERATURE,
                        AlertType.EXCESSIVE_VIBRATION,
                        AlertType.ABNORMAL_PRESSURE);
    }

    /**
     * A late event is not a duplicate. It is accepted and processed; it simply does not move the
     * current state backwards, and the result says so explicitly.
     */
    @Test
    void shouldAcceptALateEventWithoutMovingStateBackwards() {
        processor.process(event(T0.plusSeconds(10), 62.0));

        TelemetryProcessingResult late = processor.process(event(T0, 99.0));

        assertThat(late.stateAdvanced()).isFalse();
        assertThat(late.state().lastTelemetryAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(late.state().latestReadings().temperatureCelsius()).isEqualTo(62.0);
    }

    /** Rules still run on a late event: it is real data, only not the newest. */
    @Test
    void shouldStillEvaluateRulesForALateEvent() {
        processor.process(event(T0.plusSeconds(10), 62.0));

        TelemetryProcessingResult late = processor.process(event(T0, 99.0));

        assertThat(late.findings()).isNotEmpty();
    }

    /**
     * Redelivery of the same event currently reprocesses it. Documented by this test rather than
     * hidden: there is no durable deduplication yet.
     */
    @Test
    void shouldReprocessADuplicateEventBecauseThereIsNoDeduplicationYet() {
        TelemetryEvent duplicated = event(T0, 99.0);

        TelemetryProcessingResult first = processor.process(duplicated);
        TelemetryProcessingResult second = processor.process(duplicated);

        assertThat(first.findings()).hasSize(1);
        assertThat(second.findings())
                .as("the same finding is produced again; deduplication arrives in M5/M6")
                .hasSize(1);
        assertThat(second.stateAdvanced())
                .as("a redelivery carries the same timestamp, so it does not advance state")
                .isFalse();
    }

    @Test
    void shouldStillUpdateStateForAnUnregisteredMachine() {
        UUID unknown = UUID.randomUUID();
        TelemetryEvent fromUnknown = new TelemetryEvent(UUID.randomUUID(), unknown, T0,
                new TelemetryReadings(99.0, 2.0, 5.0, 30.0, 1400.0));

        TelemetryProcessingResult result = processor.process(fromUnknown);

        assertThat(result.state().machineId()).isEqualTo(unknown);
        assertThat(result.findings())
                .as("rules need machine metadata, so they are skipped rather than guessed")
                .isEmpty();
        assertThat(stateStore.find(unknown)).isPresent();
    }
}
