package com.sentinel.machine.domain;

/**
 * Category of industrial equipment.
 *
 * <p>The type is what will later make normal operating ranges differ: a turbine idles at a
 * rotation speed a pump never reaches, so a single global threshold set would be meaningless.
 * That per-type behaviour belongs to the simulator (M2) and to rule configuration; here the
 * type is only recorded.
 */
public enum MachineType {
    PUMP,
    COMPRESSOR,
    TURBINE,
    MOTOR,
    GENERATOR
}
