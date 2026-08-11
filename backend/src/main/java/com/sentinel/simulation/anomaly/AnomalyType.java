package com.sentinel.simulation.anomaly;

import java.util.Optional;

import com.sentinel.simulation.Signal;

/**
 * A fault a simulated machine can develop.
 *
 * <p>Every type except one works the same way: it drags a single {@link Signal} away from its
 * nominal value by a default magnitude. {@link #COMMUNICATION_LOSS} is structurally different —
 * it affects no measurement at all, it stops the machine from reporting. That difference is
 * modelled here rather than by a branch at the call site, so the tick loop reads the same for
 * every fault.
 *
 * @see #defaultIntensity() for the magnitude each fault applies
 */
public enum AnomalyType {

    /** Bearing friction or cooling failure: temperature climbs well past its critical threshold. */
    OVERHEATING(Signal.TEMPERATURE, 45.0),

    /** Misalignment or bearing wear: vibration rises into the critical band. */
    EXCESSIVE_VIBRATION(Signal.VIBRATION, 14.0),

    /** A blockage driving pressure up. A leak is the same fault with a negative intensity. */
    PRESSURE_FAULT(Signal.PRESSURE, 8.0),

    /** Mechanical binding or a failing winding: the machine draws far more current than rated. */
    POWER_OVERLOAD(Signal.POWER, 1.8),

    /**
     * The link to the machine drops. No reading is distorted — the machine simply stops being
     * heard, which is precisely the signal that offline detection consumes. Emitting an event
     * marked "offline" instead would defeat the purpose: absence of telemetry is the evidence.
     */
    COMMUNICATION_LOSS(null, 0.0);

    private final Signal affectedSignal;
    private final double defaultIntensity;

    AnomalyType(Signal affectedSignal, double defaultIntensity) {
        this.affectedSignal = affectedSignal;
        this.defaultIntensity = defaultIntensity;
    }

    /** The signal this fault distorts, empty when the fault suppresses reporting instead. */
    public Optional<Signal> affectedSignal() {
        return Optional.ofNullable(affectedSignal);
    }

    /** Whether a machine in this state stops emitting telemetry entirely. */
    public boolean suppressesTelemetry() {
        return affectedSignal == null;
    }

    /**
     * How far the fault pulls its signal from nominal at full strength.
     *
     * <p>Absolute for every type except {@link #POWER_OVERLOAD}, where it is a multiplier of the
     * machine's nominal power: a fixed offset in kilowatts would be negligible on a 180 kW
     * turbine and catastrophic on a 22 kW motor.
     */
    public double defaultIntensity() {
        return defaultIntensity;
    }

    /** Whether {@link #defaultIntensity()} scales with the machine's nominal value. */
    public boolean intensityIsRelative() {
        return this == POWER_OVERLOAD;
    }
}
