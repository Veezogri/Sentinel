package com.sentinel.machine.domain;

/**
 * Administrative mode of a machine, declared by an operator rather than inferred from telemetry.
 *
 * <p>This is the third axis that a single status enum would have collapsed: maintenance is
 * neither a connectivity state nor a health state. A machine under maintenance may legitimately
 * be silent, or produce readings that would otherwise be alarming, which is why alert
 * suppression will key off this mode (M6) rather than off health.
 */
public enum OperationalMode {

    /** Normal supervision applies. */
    IN_SERVICE,

    /** Planned intervention in progress. */
    MAINTENANCE
}
