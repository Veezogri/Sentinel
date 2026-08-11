package com.sentinel.machine.application;

import java.util.Optional;
import java.util.UUID;

import com.sentinel.machine.domain.Machine;

/**
 * Knows which machines exist and what they are.
 *
 * <p>Needed because a telemetry event carries only a {@code machineId}, while rule evaluation
 * takes an {@link com.sentinel.rule.domain.EvaluationContext} built from the machine itself —
 * that is what will let thresholds depend on machine type. Telemetry deliberately does not carry
 * the machine's name and type: registry data on every sample would be duplicated a thousand times
 * a second and would go stale the moment a machine is renamed.
 *
 * <p>Like {@link MachineStateStore}, this is the seam for a real store — the {@code machines}
 * table in M4. The in-memory implementation is a stand-in, not a design.
 */
public interface MachineRegistry {

    Optional<Machine> findById(UUID machineId);

    void register(Machine machine);

    int size();
}
