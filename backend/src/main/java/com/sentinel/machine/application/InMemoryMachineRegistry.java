package com.sentinel.machine.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.sentinel.machine.domain.Machine;

/**
 * The machine registry held in memory, forgotten on restart.
 *
 * <p>Populated at startup by whatever creates the fleet — today, the simulation runtime. Replaced
 * by the PostgreSQL-backed registry in M4.
 *
 * <p>Unlike {@link InMemoryMachineStateStore}, every operation here is a single map action rather
 * than a read-modify-write, so a plain {@link ConcurrentHashMap} is sufficient on its own.
 */
public class InMemoryMachineRegistry implements MachineRegistry {

    private final Map<UUID, Machine> machines = new ConcurrentHashMap<>();

    @Override
    public Optional<Machine> findById(UUID machineId) {
        return Optional.ofNullable(machines.get(machineId));
    }

    @Override
    public void register(Machine machine) {
        machines.put(machine.id(), machine);
    }

    @Override
    public int size() {
        return machines.size();
    }
}
