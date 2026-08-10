package com.sentinel.machine.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A supervised piece of industrial equipment, as registered in Sentinel.
 *
 * <p>This holds only <em>identity and configuration</em>: the facts that change when an operator
 * edits the fleet, not when a measurement arrives. Everything that moves with telemetry lives in
 * {@link MachineState}.
 *
 * <p>That split is deliberate and has a concrete cost motivation. A machine reports roughly once
 * per second; its name and type change perhaps once a year. Holding both in one object would mean
 * rewriting the registry record on every single event — turning a low-volume relational row into
 * a hot write path. Separating them lets the registry live in PostgreSQL (M4) and the runtime
 * state in Redis (M5), each with the access pattern it actually has.
 *
 * <p>Immutable: mutators return a new instance, so an instance handed to another thread can never
 * be observed half-updated.
 */
public record Machine(
        UUID id,
        String name,
        MachineType type,
        OperationalMode operationalMode,
        Instant registeredAt) {

    public Machine {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(operationalMode, "operationalMode must not be null");
        Objects.requireNonNull(registeredAt, "registeredAt must not be null");

        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** Registers a machine that is immediately under normal supervision. */
    public static Machine register(UUID id, String name, MachineType type, Instant registeredAt) {
        return new Machine(id, name, type, OperationalMode.IN_SERVICE, registeredAt);
    }

    public Machine withOperationalMode(OperationalMode newMode) {
        Objects.requireNonNull(newMode, "newMode must not be null");
        return newMode == operationalMode ? this : new Machine(id, name, type, newMode, registeredAt);
    }

    /**
     * Whether alerting should currently apply to this machine. Readings taken while an engineer
     * has the casing open are not evidence of a fault.
     */
    public boolean isUnderSupervision() {
        return operationalMode == OperationalMode.IN_SERVICE;
    }
}
