package com.sentinel.simulation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Supplier;

import com.sentinel.machine.domain.Machine;
import com.sentinel.machine.domain.MachineType;
import com.sentinel.simulation.anomaly.AnomalyType;
import com.sentinel.telemetry.domain.TelemetryEvent;

/**
 * Drives a fleet of {@link VirtualMachine}s across simulated time.
 *
 * <h2>Simulated time, not wall-clock time</h2>
 * {@link #tick()} advances an internal instant by the configured interval and returns the events
 * produced. It never sleeps. Generating an hour of telemetry takes milliseconds, and a test can
 * fast-forward past a two-minute fault without waiting. Real-time scheduling, when it is needed,
 * belongs to whatever drives this engine — not inside it.
 *
 * <h2>Determinism</h2>
 * Every random decision in a run derives from {@link SimulationConfig#seed()}: machine identity,
 * per-tick noise, when a fault starts and which one. Each machine additionally gets its own
 * generator, split from the master, so that changing the fleet size does not shift the trajectory
 * of the machines that were already there.
 *
 * <p>Event identifiers are the one exception. They come from an injectable supplier defaulting to
 * {@link UUID#randomUUID()}, because a producer that derived them from a seed would emit
 * colliding identifiers across two processes started with the same configuration — and event
 * identity is what duplicate detection will rest on. Tests that need reproducible identifiers
 * pass their own supplier.
 *
 * <h2>Single-threaded</h2>
 * Deliberately. Correctness and reproducibility are worth more here than throughput, and a
 * parallel simulator would trade an exactly reproducible run for a faster one before anything has
 * shown generation to be a bottleneck.
 */
public final class SimulationEngine {

    private final SimulationConfig config;
    private final Supplier<UUID> eventIds;
    private final Map<UUID, VirtualMachine> fleet;

    private Instant currentTime;
    private long tickCount;

    public SimulationEngine(SimulationConfig config) {
        this(config, UUID::randomUUID);
    }

    public SimulationEngine(SimulationConfig config, Supplier<UUID> eventIds) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.eventIds = Objects.requireNonNull(eventIds, "eventIds must not be null");
        this.currentTime = config.startTime();
        this.fleet = buildFleet(config);
    }

    /**
     * Produces the next round of telemetry: at most one event per machine, at the current
     * simulated instant, then advances the clock by one interval.
     *
     * <p>The result can be shorter than the fleet — machines in communication loss contribute
     * nothing. Ordering is stable across runs, following fleet creation order.
     */
    public List<TelemetryEvent> tick() {
        Instant at = currentTime;
        List<TelemetryEvent> produced = new ArrayList<>(fleet.size());

        for (VirtualMachine machine : fleet.values()) {
            machine.maybeStartRandomAnomaly(at, config.anomalyProbability(), config.anomalyDuration());
            machine.advanceTo(at, eventIds).ifPresent(produced::add);
        }

        currentTime = currentTime.plus(config.tickInterval());
        tickCount++;
        return List.copyOf(produced);
    }

    /** Runs several rounds and returns everything produced, in order. */
    public List<TelemetryEvent> run(int ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must not be negative, got " + ticks);
        }
        List<TelemetryEvent> all = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            all.addAll(tick());
        }
        return List.copyOf(all);
    }

    /**
     * Forces a fault on one machine, bypassing probability entirely.
     *
     * <p>This is how tests should create faults: a probabilistic fault would make the test depend
     * on the random stream, so that retuning an unrelated profile could silently stop exercising
     * the scenario.
     *
     * @throws IllegalArgumentException if no machine in the fleet has that identifier
     */
    public void triggerAnomaly(UUID machineId, AnomalyType type, Duration duration) {
        machineOrFail(machineId).startAnomaly(type, currentTime, duration);
    }

    /** Forces a fault at a chosen magnitude; a negative intensity drives the signal downward. */
    public void triggerAnomaly(UUID machineId, AnomalyType type, Duration duration, double intensity) {
        machineOrFail(machineId).startAnomaly(type, currentTime, duration, intensity);
    }

    /** The simulated instant the next tick will carry. */
    public Instant currentTime() {
        return currentTime;
    }

    public long tickCount() {
        return tickCount;
    }

    /** The fleet, in creation order. */
    public List<Machine> machines() {
        return fleet.values().stream().map(VirtualMachine::machine).toList();
    }

    /** Access to a simulated machine's internals, for assertions and diagnostics. */
    public VirtualMachine machineOrFail(UUID machineId) {
        VirtualMachine machine = fleet.get(machineId);
        if (machine == null) {
            throw new IllegalArgumentException("no simulated machine with id " + machineId);
        }
        return machine;
    }

    /**
     * Creates the fleet, cycling through machine types so that any fleet size covers several of
     * them, and numbering names within each type: {@code PUMP-001}, {@code COMPRESSOR-001}, …
     */
    private static Map<UUID, VirtualMachine> buildFleet(SimulationConfig config) {
        SplittableRandom master = new SplittableRandom(config.seed());
        MachineType[] types = MachineType.values();
        int[] countPerType = new int[types.length];

        Map<UUID, VirtualMachine> fleet = new LinkedHashMap<>();
        for (int index = 0; index < config.machineCount(); index++) {
            int typeIndex = index % types.length;
            MachineType type = types[typeIndex];
            String name = "%s-%03d".formatted(type.name(), ++countPerType[typeIndex]);

            // Derived from the seed so a rerun addresses the same machine identifiers.
            UUID id = new UUID(master.nextLong(), master.nextLong());
            Machine machine = Machine.register(id, name, type, config.startTime());

            fleet.put(id, new VirtualMachine(machine, MachineProfiles.forType(type), master.split()));
        }
        return fleet;
    }
}
