package com.sentinel.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.sentinel.machine.domain.MachineType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;
import com.sentinel.testsupport.SequentialUuids;

/**
 * Properties of a healthy machine's readings over time.
 *
 * <p>These assert behaviour, not exact values: pinning "tick 7 is 62.43" would break on any
 * retuning of a profile while proving nothing about the model.
 */
class SignalEvolutionTest {

    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final long SEED = 7L;

    private static List<TelemetryEvent> healthyRun(int ticks) {
        return new SimulationEngine(SimulationConfig.of(1, SEED, START), new SequentialUuids()).run(ticks);
    }

    /**
     * The defining property of this simulator: consecutive readings are related. An independent
     * draw per tick would produce 65, 112, 41 — plausible individually, meaningless as a series.
     */
    @Test
    void shouldMoveTemperatureInSmallStepsBetweenConsecutiveTicks() {
        List<TelemetryEvent> events = healthyRun(500);

        for (int i = 1; i < events.size(); i++) {
            double previous = events.get(i - 1).readings().temperatureCelsius();
            double current = events.get(i).readings().temperatureCelsius();

            assertThat(Math.abs(current - previous))
                    .as("temperature step between tick %d and %d", i - 1, i)
                    .isLessThan(5.0);
        }
    }

    @Test
    void shouldMoveEverySignalInSmallStepsRelativeToItsOwnScale() {
        List<TelemetryEvent> events = healthyRun(300);

        for (int i = 1; i < events.size(); i++) {
            TelemetryReadings previous = events.get(i - 1).readings();
            TelemetryReadings current = events.get(i).readings();

            assertThat(relativeStep(previous.vibrationMillimetresPerSecond(),
                    current.vibrationMillimetresPerSecond())).isLessThan(0.5);
            assertThat(relativeStep(previous.pressureBar(), current.pressureBar())).isLessThan(0.5);
            assertThat(relativeStep(previous.powerConsumptionKilowatts(),
                    current.powerConsumptionKilowatts())).isLessThan(0.5);
            assertThat(relativeStep(previous.rotationSpeedRpm(), current.rotationSpeedRpm()))
                    .isLessThan(0.5);
        }
    }

    /**
     * The reason for mean reversion rather than a plain random walk. A walk has unbounded
     * variance and would drift somewhere absurd over a long run; this must not.
     */
    @Test
    void shouldStayNearNominalOverATenThousandTickRun() {
        List<TelemetryEvent> events = healthyRun(10_000);
        double nominal = MachineProfiles.forType(MachineType.PUMP).of(Signal.TEMPERATURE).nominal();

        double min = events.stream().mapToDouble(e -> e.readings().temperatureCelsius()).min().orElseThrow();
        double max = events.stream().mapToDouble(e -> e.readings().temperatureCelsius()).max().orElseThrow();

        assertThat(min).isGreaterThan(nominal - 10);
        assertThat(max).isLessThan(nominal + 10);
    }

    /** Stationary, not merely bounded: the end of a long run is centred where the start was. */
    @Test
    void shouldNotDriftAwayFromNominalOverTime() {
        List<TelemetryEvent> events = healthyRun(10_000);
        double nominal = MachineProfiles.forType(MachineType.PUMP).of(Signal.TEMPERATURE).nominal();

        double lastThousandMean = events.stream()
                .skip(9_000)
                .mapToDouble(e -> e.readings().temperatureCelsius())
                .average()
                .orElseThrow();

        assertThat(lastThousandMean).isCloseTo(nominal, org.assertj.core.data.Offset.offset(2.0));
    }

    /** A healthy fleet must not trip the default rules, or every alert becomes noise. */
    @Test
    void shouldKeepHealthyReadingsInsideTheDefaultRuleBands() {
        List<TelemetryEvent> events =
                new SimulationEngine(SimulationConfig.of(20, SEED, START), new SequentialUuids()).run(500);

        assertThat(events).allSatisfy(event -> {
            TelemetryReadings readings = event.readings();
            assertThat(readings.temperatureCelsius()).isLessThan(80.0);
            assertThat(readings.vibrationMillimetresPerSecond()).isLessThan(8.0);
            assertThat(readings.pressureBar()).isBetween(1.0, 10.0);
        });
    }

    @ParameterizedTest
    @EnumSource(MachineType.class)
    void shouldRespectSensorBoundsForEveryMachineType(MachineType type) {
        MachineProfile profile = MachineProfiles.forType(type);

        for (Signal signal : Signal.values()) {
            SignalProfile signalProfile = profile.of(signal);
            assertThat(signalProfile.nominal())
                    .as("%s nominal for %s", signal, type)
                    .isBetween(signalProfile.min(), signalProfile.max());
        }
    }

    private static double relativeStep(double previous, double current) {
        double scale = Math.max(Math.abs(previous), 1.0);
        return Math.abs(current - previous) / scale;
    }
}
