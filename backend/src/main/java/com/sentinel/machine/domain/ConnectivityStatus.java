package com.sentinel.machine.domain;

/**
 * Whether Sentinel is still hearing from a machine.
 *
 * <p>This answers "is telemetry arriving?", never "is the equipment healthy?" — the two are
 * independent, and a machine can be perfectly reachable while overheating. See
 * {@link HealthStatus} for the other axis.
 *
 * <p>This value is always <em>derived</em> from the time elapsed since the last received
 * event; it is never stored. See {@link MachineState#connectivityAt}.
 */
public enum ConnectivityStatus {

    /** Telemetry was received recently enough. */
    ONLINE,

    /** No telemetry within the configured window — the machine stopped reporting. */
    OFFLINE
}
