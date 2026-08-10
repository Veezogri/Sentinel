package com.sentinel.machine.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;

/**
 * What Sentinel currently knows about a machine: its last readings and its assessed health.
 *
 * <p>This is the "snapshot" half of the event/snapshot pair. A separate {@code TelemetrySnapshot}
 * class was considered and rejected: it would have held exactly a {@link TelemetryReadings} plus
 * the instant it was taken, which is precisely what this record already carries. Introducing a
 * near-duplicate type to name a concept that has no behaviour of its own would add indirection
 * without removing any ambiguity.
 *
 * <p>Deliberately free of any Redis or persistence concern, even though this is the object that
 * will be cached in M5. It is a business object first; how it is stored is decided elsewhere.
 *
 * <h2>Why connectivity is not a field</h2>
 * {@link ConnectivityStatus} is a pure function of how long ago the last event arrived, so
 * storing it would create a value that is correct when written and silently wrong a minute
 * later — and would force a periodic sweep over every machine just to keep it accurate. It is
 * computed on read instead, by {@link #connectivityAt}. {@link HealthStatus} is stored, because
 * it is the recorded outcome of a rule evaluation and cannot be recomputed from a clock.
 */
public record MachineState(
        UUID machineId,
        TelemetryReadings latestReadings,
        Instant lastTelemetryAt,
        HealthStatus healthStatus,
        Instant lastUpdatedAt) {

    public MachineState {
        Objects.requireNonNull(machineId, "machineId must not be null");
        Objects.requireNonNull(latestReadings, "latestReadings must not be null");
        Objects.requireNonNull(lastTelemetryAt, "lastTelemetryAt must not be null");
        Objects.requireNonNull(healthStatus, "healthStatus must not be null");
        Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt must not be null");
    }

    /**
     * Builds the state of a machine that has just reported for the first time.
     *
     * <p>There is intentionally no "empty" state for a registered machine that has never
     * reported: it would force {@code latestReadings} to be nullable everywhere for the sake of
     * a transient situation. A machine with no state yet is represented by the absence of a
     * state, which callers already have to handle.
     *
     * @param processedAt when Sentinel handled the event, distinct from when the machine
     *                    measured it — the gap is ingestion lag, and hiding it would make late
     *                    or replayed data indistinguishable from fresh data
     */
    public static MachineState fromFirstEvent(TelemetryEvent event, HealthStatus health, Instant processedAt) {
        Objects.requireNonNull(event, "event must not be null");
        return new MachineState(event.machineId(), event.readings(), event.occurredAt(), health, processedAt);
    }

    /**
     * Applies a newer event, returning the resulting state.
     *
     * <p>Events that are not strictly newer than the current one are ignored and {@code this} is
     * returned unchanged. Kafka preserves order within a partition, and telemetry is keyed by
     * machine so a machine's events share a partition — but that guarantee does not survive a
     * retry, a replay from an earlier offset, or a rebalance mid-batch. Without this check, a
     * redelivered old sample would overwrite current state with stale values, which is the more
     * damaging failure of the two.
     *
     * @throws IllegalArgumentException if the event belongs to a different machine
     */
    public MachineState apply(TelemetryEvent event, HealthStatus health, Instant processedAt) {
        Objects.requireNonNull(event, "event must not be null");
        if (!machineId.equals(event.machineId())) {
            throw new IllegalArgumentException(
                    "event belongs to machine " + event.machineId() + ", not " + machineId);
        }
        if (!event.occurredAt().isAfter(lastTelemetryAt)) {
            return this;
        }
        return new MachineState(machineId, event.readings(), event.occurredAt(), health, processedAt);
    }

    /**
     * Derives connectivity at a given instant.
     *
     * @param now          the reference instant, passed in rather than read from the system clock
     *                     so that offline detection is deterministically testable
     * @param offlineAfter how much silence is tolerated before a machine counts as offline
     */
    public ConnectivityStatus connectivityAt(Instant now, Duration offlineAfter) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(offlineAfter, "offlineAfter must not be null");
        if (offlineAfter.isNegative()) {
            throw new IllegalArgumentException("offlineAfter must not be negative");
        }
        Duration silence = Duration.between(lastTelemetryAt, now);
        return silence.compareTo(offlineAfter) > 0 ? ConnectivityStatus.OFFLINE : ConnectivityStatus.ONLINE;
    }
}
