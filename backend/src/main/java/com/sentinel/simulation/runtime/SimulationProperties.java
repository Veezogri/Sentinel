package com.sentinel.simulation.runtime;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised settings for the simulated fleet.
 *
 * <p>Typed properties are used here rather than scattered {@code @Value} strings because these
 * five settings are read together, validated together and mean nothing apart. They are not applied
 * to every setting in the project on principle — a single boolean does not need a class.
 *
 * @param enabled            whether to run the simulator at all. Off by default: the ingestion
 *                           pipeline must be usable against a real producer without the backend
 *                           inventing traffic of its own
 * @param machineCount       size of the simulated fleet
 * @param tickInterval       wall-clock period between rounds, and also the simulated time each
 *                           round advances, so simulated and real time stay aligned when running
 *                           live
 * @param anomalyProbability chance that one healthy machine develops a fault on one tick
 * @param anomalyDuration    how long an injected fault lasts
 * @param seed               makes a run reproducible end to end
 */
@ConfigurationProperties(prefix = "sentinel.simulation")
public record SimulationProperties(
        boolean enabled,
        int machineCount,
        Duration tickInterval,
        double anomalyProbability,
        Duration anomalyDuration,
        long seed) {
}
