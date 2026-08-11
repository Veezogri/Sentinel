package com.sentinel.simulation.anomaly;

/**
 * Where a fault sits in its own progression.
 *
 * <p>Faults are not instantaneous: a bearing heats up over minutes and cools down over minutes.
 * These phases are what make the generated series look like a real incident rather than a spike,
 * and what makes the resulting alerts worth deduplicating.
 */
public enum AnomalyPhase {

    /** The fault is taking hold; its effect ramps from nothing to full strength. */
    DEVELOPING,

    /** The fault is at full strength and holding. */
    ACTIVE,

    /** The fault is clearing; its effect ramps back down to nothing. */
    RECOVERING,

    /** The fault has run its course and no longer affects the machine. */
    FINISHED
}
