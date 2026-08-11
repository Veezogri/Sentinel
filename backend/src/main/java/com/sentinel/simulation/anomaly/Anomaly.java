package com.sentinel.simulation.anomaly;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.sentinel.simulation.Signal;

/**
 * A fault in progress on a simulated machine.
 *
 * <p>The important property is that an anomaly is a <em>period</em>, not an event. Its strength
 * follows an envelope over its lifetime — ramping up, holding, ramping down — so a single fault
 * spans many ticks and produces a continuous curve. A fault that lasted one tick would be
 * indistinguishable from noise, and would make deduplication, cooldown and resolution impossible
 * to exercise.
 *
 * <p>The envelope is applied to the signal's <em>target</em>, not to the reading itself. Because
 * the reading chases its target through mean reversion, both the climb and the recovery come out
 * of that one mechanism: there is no separate recovery code, and no way to accidentally produce a
 * discontinuous jump.
 *
 * <p>Immutable, and its timing is expressed in simulated instants, so it never consults a clock.
 *
 * @param intensity the offset applied to the target at full strength; may be negative, which is
 *                  how {@link AnomalyType#PRESSURE_FAULT} expresses a leak rather than a blockage
 */
public record Anomaly(AnomalyType type, Instant startedAt, Duration duration, double intensity) {

    /**
     * Fraction of the lifetime spent ramping in, and again ramping out. At 0.25 a fault spends a
     * quarter of its life developing, half at full strength, and a quarter recovering.
     */
    private static final double RAMP_FRACTION = 0.25;

    public Anomaly {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive, got " + duration);
        }
        if (!Double.isFinite(intensity)) {
            throw new IllegalArgumentException("intensity must be finite, got " + intensity);
        }
    }

    /**
     * Strength of the fault at a given instant, from 0 (no effect) to 1 (full effect).
     *
     * <p>Zero once the anomaly has finished, so an expired fault contributes nothing even if it
     * has not yet been cleared from the machine.
     */
    public double envelopeAt(Instant now) {
        double progress = progressAt(now);
        if (progress <= 0 || progress >= 1) {
            return 0.0;
        }
        if (progress < RAMP_FRACTION) {
            return progress / RAMP_FRACTION;
        }
        if (progress > 1 - RAMP_FRACTION) {
            return (1 - progress) / RAMP_FRACTION;
        }
        return 1.0;
    }

    /** The offset this fault contributes to its signal's target at a given instant. */
    public double offsetAt(Instant now) {
        return intensity * envelopeAt(now);
    }

    /** Whether this fault distorts the given signal. Always false for a fault that only silences. */
    public boolean affects(Signal signal) {
        return type.affectedSignal().filter(affected -> affected == signal).isPresent();
    }

    public AnomalyPhase phaseAt(Instant now) {
        double progress = progressAt(now);
        if (progress >= 1) {
            return AnomalyPhase.FINISHED;
        }
        if (progress < RAMP_FRACTION) {
            return AnomalyPhase.DEVELOPING;
        }
        if (progress > 1 - RAMP_FRACTION) {
            return AnomalyPhase.RECOVERING;
        }
        return AnomalyPhase.ACTIVE;
    }

    public boolean hasFinishedBy(Instant now) {
        return phaseAt(now) == AnomalyPhase.FINISHED;
    }

    /** Position within the fault's lifetime, 0 at its start and 1 at its end. */
    private double progressAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        double elapsedNanos = Duration.between(startedAt, now).toNanos();
        return elapsedNanos / duration.toNanos();
    }
}
