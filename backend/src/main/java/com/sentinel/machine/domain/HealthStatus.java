package com.sentinel.machine.domain;

/**
 * How the equipment is behaving, according to the rules evaluated against its telemetry.
 *
 * <p>Independent of {@link ConnectivityStatus}: keeping the two apart is what allows a machine
 * that stops reporting to retain its last known health instead of having it overwritten by the
 * mere fact of going silent.
 *
 * <p>Unlike connectivity, this value is stored: it is the outcome of a rule evaluation at a
 * point in time, not a function of the current clock, so it cannot be recomputed on read.
 */
public enum HealthStatus {

    /** No rule triggered. */
    NORMAL,

    /** At least one rule triggered at warning level, none at critical. */
    WARNING,

    /** At least one rule triggered at critical level. */
    CRITICAL
}
