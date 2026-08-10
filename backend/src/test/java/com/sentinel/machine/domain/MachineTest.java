package com.sentinel.machine.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.sentinel.testsupport.DomainFixtures;

class MachineTest {

    @Test
    void shouldRegisterMachineUnderNormalSupervision() {
        Machine machine = DomainFixtures.machine();

        assertThat(machine.operationalMode()).isEqualTo(OperationalMode.IN_SERVICE);
        assertThat(machine.isUnderSupervision()).isTrue();
    }

    @Test
    void shouldNotBeUnderSupervisionWhileInMaintenance() {
        Machine underMaintenance = DomainFixtures.machine().withOperationalMode(OperationalMode.MAINTENANCE);

        assertThat(underMaintenance.isUnderSupervision()).isFalse();
    }

    @Test
    void shouldLeaveOriginalUnchangedWhenModeChanges() {
        Machine machine = DomainFixtures.machine();

        machine.withOperationalMode(OperationalMode.MAINTENANCE);

        assertThat(machine.operationalMode()).isEqualTo(OperationalMode.IN_SERVICE);
    }

    @Test
    void shouldReturnSameInstanceWhenModeIsUnchanged() {
        Machine machine = DomainFixtures.machine();

        assertThat(machine.withOperationalMode(OperationalMode.IN_SERVICE)).isSameAs(machine);
    }

    @Test
    void shouldTrimName() {
        Machine machine = Machine.register(
                DomainFixtures.MACHINE_ID, "  Pump A-01  ", MachineType.PUMP, DomainFixtures.NOW);

        assertThat(machine.name()).isEqualTo("Pump A-01");
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> Machine.register(
                DomainFixtures.MACHINE_ID, "   ", MachineType.PUMP, DomainFixtures.NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }
}
