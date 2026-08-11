package com.sentinel.simulation.anomaly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.sentinel.simulation.Signal;

class AnomalyTest {

    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final Duration DURATION = Duration.ofSeconds(100);

    private static Anomaly overheating() {
        return new Anomaly(AnomalyType.OVERHEATING, START, DURATION, 40.0);
    }

    private static Instant at(int secondsIn) {
        return START.plusSeconds(secondsIn);
    }

    @Test
    void shouldRampUpHoldThenRampDown() {
        Anomaly anomaly = overheating();

        assertThat(anomaly.phaseAt(at(0))).isEqualTo(AnomalyPhase.DEVELOPING);
        assertThat(anomaly.phaseAt(at(10))).isEqualTo(AnomalyPhase.DEVELOPING);
        assertThat(anomaly.phaseAt(at(50))).isEqualTo(AnomalyPhase.ACTIVE);
        assertThat(anomaly.phaseAt(at(90))).isEqualTo(AnomalyPhase.RECOVERING);
        assertThat(anomaly.phaseAt(at(100))).isEqualTo(AnomalyPhase.FINISHED);
        assertThat(anomaly.phaseAt(at(200))).isEqualTo(AnomalyPhase.FINISHED);
    }

    @Test
    void shouldReachFullStrengthOnlyDuringTheActivePhase() {
        Anomaly anomaly = overheating();

        assertThat(anomaly.envelopeAt(at(0))).isZero();
        assertThat(anomaly.envelopeAt(at(12))).isBetween(0.4, 0.6);
        assertThat(anomaly.envelopeAt(at(50))).isEqualTo(1.0);
        assertThat(anomaly.envelopeAt(at(88))).isBetween(0.4, 0.6);
        assertThat(anomaly.envelopeAt(at(100))).isZero();
    }

    /** The envelope is what guarantees no discontinuity: it must never jump. */
    @Test
    void shouldChangeStrengthContinuously() {
        Anomaly anomaly = overheating();
        double previous = anomaly.envelopeAt(START);

        for (int second = 1; second <= 120; second++) {
            double current = anomaly.envelopeAt(at(second));
            assertThat(Math.abs(current - previous))
                    .as("envelope step at second %d", second)
                    .isLessThan(0.1);
            previous = current;
        }
    }

    @Test
    void shouldContributeNothingOnceFinished() {
        assertThat(overheating().offsetAt(at(150))).isZero();
        assertThat(overheating().hasFinishedBy(at(150))).isTrue();
    }

    @Test
    void shouldScaleOffsetByIntensity() {
        Anomaly strong = new Anomaly(AnomalyType.OVERHEATING, START, DURATION, 40.0);
        Anomaly mild = new Anomaly(AnomalyType.OVERHEATING, START, DURATION, 10.0);

        assertThat(strong.offsetAt(at(50))).isEqualTo(40.0);
        assertThat(mild.offsetAt(at(50))).isEqualTo(10.0);
    }

    /** A leak is a pressure fault with a negative intensity, not a separate anomaly type. */
    @Test
    void shouldSupportNegativeIntensityForADownwardFault() {
        Anomaly leak = new Anomaly(AnomalyType.PRESSURE_FAULT, START, DURATION, -4.0);

        assertThat(leak.offsetAt(at(50))).isEqualTo(-4.0);
    }

    @Test
    void shouldOnlyAffectItsOwnSignal() {
        Anomaly anomaly = overheating();

        assertThat(anomaly.affects(Signal.TEMPERATURE)).isTrue();
        assertThat(anomaly.affects(Signal.VIBRATION)).isFalse();
        assertThat(anomaly.affects(Signal.PRESSURE)).isFalse();
    }

    @Test
    void shouldAffectNoSignalWhenItOnlySilencesTheMachine() {
        Anomaly lost = new Anomaly(AnomalyType.COMMUNICATION_LOSS, START, DURATION, 0.0);

        for (Signal signal : Signal.values()) {
            assertThat(lost.affects(signal)).isFalse();
        }
        assertThat(AnomalyType.COMMUNICATION_LOSS.suppressesTelemetry()).isTrue();
        assertThat(AnomalyType.COMMUNICATION_LOSS.affectedSignal()).isEmpty();
    }

    @Test
    void shouldRejectNonPositiveDuration() {
        assertThatThrownBy(() -> new Anomaly(AnomalyType.OVERHEATING, START, Duration.ZERO, 10.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duration");
    }
}
