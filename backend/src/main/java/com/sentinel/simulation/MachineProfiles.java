package com.sentinel.simulation;

import java.util.EnumMap;
import java.util.Map;

import com.sentinel.machine.domain.MachineType;

/**
 * The behavioural profile of every {@link MachineType}, defined in exactly one place.
 *
 * <p>Two constraints shaped the numbers below.
 *
 * <p><strong>Plausibility, not physics.</strong> A pump runs cooler and slower than a turbine and
 * draws a fraction of the power; a compressor works at the highest pressure. That ordering is what
 * makes a dashboard look like an industrial system. Reproducing real thermodynamics would add no
 * value to a monitoring platform.
 *
 * <p><strong>A healthy machine must not alert.</strong> Nominal values sit comfortably inside the
 * default rule bands — temperature under 80 °C, vibration under 8 mm/s, pressure within
 * [1, 10] bar — with several standard deviations of margin. A simulator whose idle state trips the
 * rules would make every alert meaningless.
 *
 * <p>Sensor ranges are <em>not</em> operating limits. They sit far outside the alert thresholds on
 * purpose: clamping a signal at its alert threshold would make a critical anomaly unreachable.
 */
public final class MachineProfiles {

    // Sensor ranges, shared across machine types. Each is the span a probe of that kind can
    // report at all; a value outside it means the instrument, not the machine, is at fault.
    private static final double TEMPERATURE_MIN_CELSIUS = -20.0;
    private static final double TEMPERATURE_MAX_CELSIUS = 250.0;
    private static final double VIBRATION_MAX_MM_PER_SECOND = 60.0;
    private static final double PRESSURE_MAX_BAR = 40.0;

    /** Headroom above nominal power, as a multiplier: enough for an overload to reach critical. */
    private static final double POWER_HEADROOM = 4.0;

    /** Rotation speed cannot run far above its rated value before the machine is destroyed. */
    private static final double ROTATION_SPEED_HEADROOM = 1.6;

    private static final Map<MachineType, MachineProfile> PROFILES = buildProfiles();

    private MachineProfiles() {
    }

    public static MachineProfile forType(MachineType type) {
        MachineProfile profile = PROFILES.get(type);
        if (profile == null) {
            throw new IllegalArgumentException("no simulation profile defined for " + type);
        }
        return profile;
    }

    //                                     nominal  noise  reversion
    private static Map<MachineType, MachineProfile> buildProfiles() {
        Map<MachineType, MachineProfile> profiles = new EnumMap<>(MachineType.class);

        // Centrifugal pump: moderate everything, steady duty.
        profiles.put(MachineType.PUMP, profile(
                temperature(62.0, 0.40, 0.15),
                vibration(2.5, 0.10, 0.25),
                pressure(6.0, 0.08, 0.20),
                power(35.0, 0.60, 0.20),
                rotationSpeed(1450.0, 6.0)));

        // Screw compressor: hottest of the low-speed machines and by far the highest pressure.
        profiles.put(MachineType.COMPRESSOR, profile(
                temperature(70.0, 0.50, 0.12),
                vibration(4.0, 0.15, 0.25),
                pressure(8.0, 0.10, 0.18),
                power(75.0, 1.20, 0.20),
                rotationSpeed(2900.0, 12.0)));

        // Gas turbine: very high speed and power, tightly regulated so noise is proportionally low.
        profiles.put(MachineType.TURBINE, profile(
                temperature(72.0, 0.45, 0.10),
                vibration(3.0, 0.12, 0.30),
                pressure(4.5, 0.07, 0.20),
                power(180.0, 2.50, 0.15),
                rotationSpeed(9000.0, 25.0)));

        // Electric motor: the coolest and quietest, pressure only incidental to its circuit.
        profiles.put(MachineType.MOTOR, profile(
                temperature(58.0, 0.35, 0.18),
                vibration(2.0, 0.08, 0.28),
                pressure(3.0, 0.06, 0.22),
                power(22.0, 0.40, 0.22),
                rotationSpeed(1750.0, 8.0)));

        // Generator: grid-synchronised, so its speed barely moves while power follows the load.
        profiles.put(MachineType.GENERATOR, profile(
                temperature(68.0, 0.45, 0.14),
                vibration(3.5, 0.13, 0.26),
                pressure(2.5, 0.06, 0.22),
                power(120.0, 2.00, 0.18),
                rotationSpeed(1800.0, 3.0)));

        return Map.copyOf(profiles);
    }

    private static MachineProfile profile(
            SignalProfile temperature,
            SignalProfile vibration,
            SignalProfile pressure,
            SignalProfile power,
            SignalProfile rotationSpeed) {

        Map<Signal, SignalProfile> signals = new EnumMap<>(Signal.class);
        signals.put(Signal.TEMPERATURE, temperature);
        signals.put(Signal.VIBRATION, vibration);
        signals.put(Signal.PRESSURE, pressure);
        signals.put(Signal.POWER, power);
        signals.put(Signal.ROTATION_SPEED, rotationSpeed);
        return new MachineProfile(signals);
    }

    private static SignalProfile temperature(double nominal, double noiseStdDev, double reversionRate) {
        return new SignalProfile(nominal, noiseStdDev, reversionRate,
                TEMPERATURE_MIN_CELSIUS, TEMPERATURE_MAX_CELSIUS);
    }

    private static SignalProfile vibration(double nominal, double noiseStdDev, double reversionRate) {
        return new SignalProfile(nominal, noiseStdDev, reversionRate, 0.0, VIBRATION_MAX_MM_PER_SECOND);
    }

    private static SignalProfile pressure(double nominal, double noiseStdDev, double reversionRate) {
        return new SignalProfile(nominal, noiseStdDev, reversionRate, 0.0, PRESSURE_MAX_BAR);
    }

    private static SignalProfile power(double nominal, double noiseStdDev, double reversionRate) {
        return new SignalProfile(nominal, noiseStdDev, reversionRate, 0.0, nominal * POWER_HEADROOM);
    }

    private static SignalProfile rotationSpeed(double nominal, double noiseStdDev) {
        // No anomaly targets rotation speed yet, so it only needs to absorb noise; the reversion
        // rate is high because a regulated drive holds its setpoint tightly.
        return new SignalProfile(nominal, noiseStdDev, 0.25, 0.0, nominal * ROTATION_SPEED_HEADROOM);
    }
}
