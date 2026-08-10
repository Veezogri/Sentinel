package com.sentinel.alert.domain;

/**
 * The kind of condition an alert reports.
 *
 * <p>Also the deduplication key, together with the machine: "this machine already has an open
 * HIGH_TEMPERATURE alert" is what will stop a sustained overheat from producing one alert per
 * second (M6). That role is why the set is kept small and meaningful rather than exhaustive.
 */
public enum AlertType {

    HIGH_TEMPERATURE,
    EXCESSIVE_VIBRATION,
    ABNORMAL_PRESSURE,
    HIGH_POWER_CONSUMPTION,

    /**
     * Raised from absence of telemetry rather than from its content, so no rule in the current
     * engine produces it — detection is time-driven and lands with offline detection (M5).
     */
    MACHINE_OFFLINE
}
