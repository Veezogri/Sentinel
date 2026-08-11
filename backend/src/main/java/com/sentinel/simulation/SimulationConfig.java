package com.sentinel.simulation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Everything needed to reproduce a simulation run exactly.
 *
 * <p>Plain Java, with no Spring binding and no {@code application.yml}: the simulation core is
 * usable from a unit test with one constructor call, and configuration binding is a concern of
 * whatever eventually runs it as a process.
 *
 * @param machineCount       how many machines make up the fleet
 * @param tickInterval       <em>simulated</em> time between two events of the same machine. It is
 *                           not a sleep: ten thousand ticks of a one-second interval advance the
 *                           simulated clock by nearly three hours and run in milliseconds
 * @param anomalyProbability probability that <em>one healthy machine</em> develops a fault on
 *                           <em>one tick</em>. A machine already faulty is not rolled for, so the
 *                           expected time to the next fault is {@code 1 / anomalyProbability}
 *                           ticks of healthy operation. At 0.001 and a one-second tick, a machine
 *                           averages roughly one fault every 17 simulated minutes
 * @param anomalyDuration    how long an injected fault lasts, ramp-up and recovery included
 * @param seed               seeds every random decision in the run: fleet identity, noise, fault
 *                           timing and fault type. Two runs with the same config and seed produce
 *                           byte-identical readings
 * @param startTime          simulated instant of the first tick
 */
public record SimulationConfig(
        int machineCount,
        Duration tickInterval,
        double anomalyProbability,
        Duration anomalyDuration,
        long seed,
        Instant startTime) {

    public SimulationConfig {
        if (machineCount <= 0) {
            throw new IllegalArgumentException("machineCount must be positive, got " + machineCount);
        }
        Objects.requireNonNull(tickInterval, "tickInterval must not be null");
        if (tickInterval.isZero() || tickInterval.isNegative()) {
            throw new IllegalArgumentException("tickInterval must be positive, got " + tickInterval);
        }
        if (anomalyProbability < 0 || anomalyProbability > 1) {
            throw new IllegalArgumentException(
                    "anomalyProbability must be in [0, 1], got " + anomalyProbability);
        }
        Objects.requireNonNull(anomalyDuration, "anomalyDuration must not be null");
        if (anomalyDuration.isZero() || anomalyDuration.isNegative()) {
            throw new IllegalArgumentException("anomalyDuration must be positive, got " + anomalyDuration);
        }
        Objects.requireNonNull(startTime, "startTime must not be null");
    }

    /**
     * A fleet reporting once per second with faults rare enough that healthy behaviour dominates.
     *
     * <p>{@code anomalyProbability} of zero: tests that need a fault force it explicitly, so that
     * no test depends on a probabilistic event occurring.
     */
    public static SimulationConfig of(int machineCount, long seed, Instant startTime) {
        return new SimulationConfig(
                machineCount,
                Duration.ofSeconds(1),
                0.0,
                Duration.ofMinutes(2),
                seed,
                startTime);
    }

    public SimulationConfig withAnomalyProbability(double probability) {
        return new SimulationConfig(
                machineCount, tickInterval, probability, anomalyDuration, seed, startTime);
    }
}
