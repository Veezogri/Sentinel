package com.sentinel.testsupport;

import java.time.Instant;
import java.util.UUID;

import com.sentinel.machine.domain.Machine;
import com.sentinel.machine.domain.MachineType;
import com.sentinel.rule.domain.EvaluationContext;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;

/**
 * Nominal domain objects for tests.
 *
 * <p>Exists so that a test about temperature does not have to spell out four unrelated
 * measurements. Every fixture is deliberately well inside the default thresholds, so any rule
 * that triggers in a test triggers because of the one value that test changed.
 */
public final class DomainFixtures {

    public static final UUID MACHINE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    public static final double NOMINAL_TEMPERATURE_CELSIUS = 60.0;
    public static final double NOMINAL_VIBRATION_MM_PER_SECOND = 3.0;
    public static final double NOMINAL_PRESSURE_BAR = 5.0;
    public static final double NOMINAL_POWER_KW = 40.0;
    public static final double NOMINAL_RPM = 1500.0;

    private DomainFixtures() {
    }

    public static Machine machine() {
        return Machine.register(MACHINE_ID, "Pump A-01", MachineType.PUMP, NOW.minusSeconds(3600));
    }

    public static TelemetryReadings nominalReadings() {
        return new TelemetryReadings(
                NOMINAL_TEMPERATURE_CELSIUS,
                NOMINAL_VIBRATION_MM_PER_SECOND,
                NOMINAL_PRESSURE_BAR,
                NOMINAL_POWER_KW,
                NOMINAL_RPM);
    }

    public static TelemetryReadings readingsWithTemperature(double temperatureCelsius) {
        return new TelemetryReadings(temperatureCelsius, NOMINAL_VIBRATION_MM_PER_SECOND,
                NOMINAL_PRESSURE_BAR, NOMINAL_POWER_KW, NOMINAL_RPM);
    }

    public static TelemetryReadings readingsWithVibration(double vibrationMillimetresPerSecond) {
        return new TelemetryReadings(NOMINAL_TEMPERATURE_CELSIUS, vibrationMillimetresPerSecond,
                NOMINAL_PRESSURE_BAR, NOMINAL_POWER_KW, NOMINAL_RPM);
    }

    public static TelemetryReadings readingsWithPressure(double pressureBar) {
        return new TelemetryReadings(NOMINAL_TEMPERATURE_CELSIUS, NOMINAL_VIBRATION_MM_PER_SECOND,
                pressureBar, NOMINAL_POWER_KW, NOMINAL_RPM);
    }

    public static TelemetryEvent event(TelemetryReadings readings) {
        return eventAt(NOW, readings);
    }

    public static TelemetryEvent eventAt(Instant occurredAt, TelemetryReadings readings) {
        return new TelemetryEvent(UUID.randomUUID(), MACHINE_ID, occurredAt, readings);
    }

    public static EvaluationContext contextWith(TelemetryReadings readings) {
        return new EvaluationContext(machine(), event(readings));
    }
}
