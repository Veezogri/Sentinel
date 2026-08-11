package com.sentinel.machine.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sentinel.machine.application.InMemoryMachineRegistry;
import com.sentinel.machine.application.InMemoryMachineStateStore;
import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.machine.application.MachineStateStore;

/**
 * Wires the machine stores.
 *
 * <p>Both are in-memory and therefore lost on restart. They are declared behind their interfaces
 * so that M4 and M5 can swap the implementation here and nowhere else: the registry becomes a
 * PostgreSQL table, the state store becomes Redis, and no caller changes.
 */
@Configuration
public class MachineStoreConfiguration {

    @Bean
    MachineStateStore machineStateStore() {
        return new InMemoryMachineStateStore();
    }

    @Bean
    MachineRegistry machineRegistry() {
        return new InMemoryMachineRegistry();
    }
}
