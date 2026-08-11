package com.sentinel.simulation.runtime;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.machine.domain.Machine;
import com.sentinel.simulation.SimulationConfig;
import com.sentinel.simulation.SimulationEngine;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.infrastructure.messaging.TelemetryPublisher;

/**
 * Drives the simulator on a wall-clock schedule and publishes what it produces.
 *
 * <p>This is the seam between the two clocks the project keeps apart. The engine advances
 * <em>simulated</em> time by one interval per {@code tick()} and never sleeps; this runner is what
 * decides that a tick should happen once per real second. Nothing here reaches into the simulator
 * to slow it down, and the simulator still has no idea a scheduler exists — which is why the same
 * engine can generate ten thousand ticks instantly inside a test.
 *
 * <p>The scheduler thread is Spring's, so it is a managed daemon that stops with the context; no
 * thread is started or left running by this class.
 */
public class SimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunner.class);

    private final SimulationEngine engine;
    private final TelemetryPublisher publisher;

    public SimulationRunner(
            SimulationProperties properties,
            TelemetryPublisher publisher,
            MachineRegistry machineRegistry,
            Clock clock) {

        this.publisher = publisher;
        this.engine = new SimulationEngine(new SimulationConfig(
                properties.machineCount(),
                properties.tickInterval(),
                properties.anomalyProbability(),
                properties.anomalyDuration(),
                properties.seed(),
                clock.instant()));

        // The consumer needs machine metadata to build an evaluation context, and telemetry
        // deliberately does not carry it. Until PostgreSQL holds the fleet (M4), whoever creates
        // the machines is also who announces them.
        for (Machine machine : engine.machines()) {
            machineRegistry.register(machine);
        }
        log.info("simulation ready: {} machines, tick {}, anomaly probability {}",
                properties.machineCount(), properties.tickInterval(), properties.anomalyProbability());
    }

    /**
     * Produces and publishes one round.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}: with a fixed rate, a round that takes
     * longer than its interval causes the next to fire immediately and the backlog to compound.
     * A fixed delay lets the producer fall behind real time gracefully instead of building an
     * ever-growing queue of scheduled rounds.
     */
    @Scheduled(fixedDelayString = "${sentinel.simulation.tick-interval}")
    public void publishNextRound() {
        for (TelemetryEvent event : engine.tick()) {
            publisher.publish(event);
        }
    }

    public SimulationEngine engine() {
        return engine;
    }
}
