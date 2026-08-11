package com.sentinel.simulation;

/**
 * One measurable quantity of a machine.
 *
 * <p>Exists so the simulator can treat the five measurements uniformly. Without it, every profile
 * lookup, every evolution step and every anomaly effect would need its own branch per quantity —
 * the same {@code switch} repeated in three classes, and five near-identical blocks inside each.
 * With it, a tick is a single loop over {@code Signal.values()}.
 *
 * <p>Deliberately a simulation concept, not a domain one: the domain has no reason to talk about
 * measurements generically, and {@link com.sentinel.telemetry.domain.TelemetryReadings} is
 * clearer with named fields.
 */
public enum Signal {
    TEMPERATURE,
    VIBRATION,
    PRESSURE,
    POWER,
    ROTATION_SPEED
}
