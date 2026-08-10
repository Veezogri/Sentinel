package com.sentinel.alert.domain;

import com.sentinel.machine.domain.HealthStatus;

/**
 * How serious a detected condition is.
 *
 * <p>Declared from least to most severe so that natural enum ordering is meaningful — the fleet
 * health of a machine is the worst severity among its open alerts.
 */
public enum AlertSeverity {

    /** Noteworthy, no action required. */
    INFO,

    /** Outside normal operating range; the machine needs attention. */
    WARNING,

    /** Risk of damage or failure; the machine needs immediate attention. */
    CRITICAL;

    /**
     * The machine health implied by this severity.
     *
     * <p>Placed here rather than in a mapper so the correspondence is stated once. {@code INFO}
     * maps to {@code NORMAL}: an informational finding is not a degradation of the equipment.
     */
    public HealthStatus impliedHealthStatus() {
        return switch (this) {
            case INFO -> HealthStatus.NORMAL;
            case WARNING -> HealthStatus.WARNING;
            case CRITICAL -> HealthStatus.CRITICAL;
        };
    }

    /** Returns the more severe of the two. */
    public AlertSeverity max(AlertSeverity other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
