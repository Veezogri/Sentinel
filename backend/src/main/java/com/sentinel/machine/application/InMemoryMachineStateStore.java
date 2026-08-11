package com.sentinel.machine.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sentinel.machine.domain.HealthStatus;
import com.sentinel.machine.domain.MachineState;
import com.sentinel.telemetry.domain.TelemetryEvent;

/**
 * Machine state kept in the process heap.
 *
 * <p><strong>Not durable.</strong> A restart forgets every machine, and two instances of the
 * backend would each hold their own divergent view. It exists so the pipeline can be exercised
 * end to end before Redis arrives in M5, and it must never be described as the platform's state
 * management.
 *
 * <h2>Why {@code ConcurrentHashMap} alone would be wrong</h2>
 * The update is a read-modify-write: read the current state, ask the domain whether the event is
 * newer, write the result. Each of {@code get} and {@code put} is individually thread-safe, and
 * the sequence still is not — two threads handling events for the same machine can both read the
 * same state and the second write silently discards the first. A concurrent map makes single
 * operations safe, not compound ones.
 *
 * <p>{@link ConcurrentHashMap#compute} closes that gap: it holds the bin lock for the key while
 * the remapping function runs, so the whole read-modify-write is atomic per machine, and updates
 * to different machines still proceed in parallel. The function passed to it must stay short and
 * must not perform I/O or acquire other locks — anything slow inside it blocks every other write
 * to the same key, and calling back into the map from inside it can deadlock. Here it does one
 * pure domain call, which respects that contract.
 *
 * <p>Rule evaluation stays <em>outside</em> this call for exactly that reason: it is a pure
 * function of the event, so it can run before the lock is taken.
 *
 * <p>Note that with {@code machineId} as the Kafka key, one machine's events land on one
 * partition and are handled by one thread, so this contention is rare in the current pipeline.
 * The guarantee still has to hold: a rebalance can move a partition between threads, and the
 * REST API will read this store concurrently from M7.
 */
public class InMemoryMachineStateStore implements MachineStateStore {

    private final Map<UUID, MachineState> states = new ConcurrentHashMap<>();

    @Override
    public StateUpdate apply(TelemetryEvent event, HealthStatus health, Instant processedAt) {
        // The flag is written inside the remapping function and read after it returns. That is
        // safe because compute() runs the function synchronously on this thread, so the holder
        // never escapes and needs no synchronisation of its own — it is simply an out-parameter.
        AtomicBoolean advanced = new AtomicBoolean();

        MachineState state = states.compute(event.machineId(), (machineId, current) -> {
            if (current == null) {
                advanced.set(true);
                return MachineState.fromFirstEvent(event, health, processedAt);
            }
            MachineState next = current.apply(event, health, processedAt);
            // MachineState.apply returns the same instance when it declines the event, so
            // identity is an exact answer where comparing timestamps is only a guess.
            advanced.set(next != current);
            return next;
        });

        return new StateUpdate(state, advanced.get());
    }

    @Override
    public Optional<MachineState> find(UUID machineId) {
        return Optional.ofNullable(states.get(machineId));
    }

    @Override
    public int size() {
        return states.size();
    }
}
