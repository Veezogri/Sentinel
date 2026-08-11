package com.sentinel.machine.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.sentinel.machine.domain.HealthStatus;
import com.sentinel.machine.domain.MachineState;
import com.sentinel.telemetry.domain.TelemetryEvent;

/**
 * Holds the current state of every machine.
 *
 * <p>An interface with one implementation today, which the project would normally avoid. It earns
 * its place because a second implementation is already scheduled: this is the seam where Redis
 * replaces memory in M5. Defining it now forces the question that matters — what does the caller
 * actually need? — while the answer is still cheap to change.
 *
 * <p>The answer is deliberately narrow. There is no {@code get-then-put} pair, because that shape
 * cannot be made atomic by any implementation: between the read and the write, another thread can
 * interleave. {@link #apply} is a single call so that each implementation can make it atomic with
 * whatever its storage offers — a per-key lock in memory, a Lua script or {@code WATCH/MULTI} in
 * Redis.
 */
public interface MachineStateStore {

    /**
     * Outcome of applying one event.
     *
     * @param state    the state after the event
     * @param advanced whether the event actually moved the state forward. The store reports this
     *                 rather than letting the caller infer it: comparing the returned
     *                 {@code lastTelemetryAt} to the event's own timestamp cannot distinguish
     *                 "applied" from "ignored because the timestamps were equal", which is
     *                 precisely the redelivery case
     */
    record StateUpdate(MachineState state, boolean advanced) {
    }

    /**
     * Applies an event to a machine's state.
     *
     * <p>Must be atomic per machine: concurrent calls for the same machine must not lose an
     * update, and must not observe a half-written state. Concurrency across different machines is
     * expected and should not be serialised.
     *
     * <p>Delegates the decision of whether the event is newer to
     * {@link MachineState#apply}, so the ordering rule lives in the domain rather than being
     * reimplemented by every store.
     *
     * @param processedAt when the platform handled the event, as opposed to when it occurred
     */
    StateUpdate apply(TelemetryEvent event, HealthStatus health, Instant processedAt);

    /** The last known state of a machine, empty if it has never reported. */
    Optional<MachineState> find(UUID machineId);

    /** How many machines have reported at least once. */
    int size();
}
