package com.sentinel.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.sentinel.machine.domain.MachineType;

class MachineProfilesTest {

    @ParameterizedTest
    @EnumSource(MachineType.class)
    void shouldDefineEverySignalForEveryMachineType(MachineType type) {
        MachineProfile profile = MachineProfiles.forType(type);

        assertThat(Arrays.stream(Signal.values()).map(profile::of)).doesNotContainNull();
    }

    /** Types exist to behave differently; identical profiles would make the concept decorative. */
    @Test
    void shouldGiveEachMachineTypeADistinctProfile() {
        long distinct = Arrays.stream(MachineType.values())
                .map(MachineProfiles::forType)
                .distinct()
                .count();

        assertThat(distinct).isEqualTo(MachineType.values().length);
    }

    @Test
    void shouldGiveTurbinesTheHighestRotationSpeedAndMotorsTheLowestPower() {
        double turbineRpm = nominal(MachineType.TURBINE, Signal.ROTATION_SPEED);
        double motorPower = nominal(MachineType.MOTOR, Signal.POWER);

        assertThat(turbineRpm).isEqualTo(Arrays.stream(MachineType.values())
                .mapToDouble(type -> nominal(type, Signal.ROTATION_SPEED))
                .max()
                .orElseThrow());
        assertThat(motorPower).isEqualTo(Arrays.stream(MachineType.values())
                .mapToDouble(type -> nominal(type, Signal.POWER))
                .min()
                .orElseThrow());
    }

    @Test
    void shouldGiveEveryTypeADistinctNominalTemperature() {
        assertThat(Arrays.stream(MachineType.values())
                .map(type -> nominal(type, Signal.TEMPERATURE))
                .collect(Collectors.toSet()))
                .hasSize(MachineType.values().length);
    }

    /**
     * Sensor ranges must sit well outside the alert thresholds. If a signal clamped at its warning
     * level, no anomaly could ever drive it to critical and the whole severity model would be
     * unreachable.
     */
    @ParameterizedTest
    @EnumSource(MachineType.class)
    void shouldAllowTemperatureToReachCriticalLevels(MachineType type) {
        assertThat(MachineProfiles.forType(type).of(Signal.TEMPERATURE).max())
                .isGreaterThan(120.0);
    }

    @ParameterizedTest
    @EnumSource(MachineType.class)
    void shouldLeaveHeadroomForAPowerOverload(MachineType type) {
        SignalProfile power = MachineProfiles.forType(type).of(Signal.POWER);

        assertThat(power.max()).isGreaterThan(power.nominal() * 2);
    }

    private static double nominal(MachineType type, Signal signal) {
        return MachineProfiles.forType(type).of(signal).nominal();
    }
}
