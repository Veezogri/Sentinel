package com.sentinel.simulation.runtime;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.telemetry.infrastructure.messaging.TelemetryPublisher;

/**
 * Activates the simulated fleet, and only when it is asked for.
 *
 * <p>Guarded by {@code sentinel.simulation.enabled} so that the ingestion pipeline can be run
 * against a real producer, or left idle in a test, without the backend manufacturing its own
 * traffic. Scheduling is enabled here rather than on the application class for the same reason:
 * no simulation, no scheduler.
 */
@Configuration
@EnableConfigurationProperties(SimulationProperties.class)
@ConditionalOnProperty(prefix = "sentinel.simulation", name = "enabled", havingValue = "true")
@EnableScheduling
public class SimulationRuntimeConfiguration {

    @Bean
    SimulationRunner simulationRunner(
            SimulationProperties properties,
            TelemetryPublisher publisher,
            MachineRegistry machineRegistry,
            Clock clock) {
        return new SimulationRunner(properties, publisher, machineRegistry, clock);
    }
}
