package com.sentinel.machine.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.testsupport.DomainFixtures;

class MachineStateTest {

    private static final Instant T0 = DomainFixtures.NOW;
    private static final Duration OFFLINE_AFTER = Duration.ofSeconds(30);

    private static MachineState stateAt(Instant occurredAt) {
        return MachineState.fromFirstEvent(
                DomainFixtures.eventAt(occurredAt, DomainFixtures.nominalReadings()),
                HealthStatus.NORMAL,
                occurredAt);
    }

    @Test
    void shouldBuildInitialStateFromFirstEvent() {
        TelemetryEvent event = DomainFixtures.eventAt(T0, DomainFixtures.readingsWithTemperature(72.0));

        MachineState state = MachineState.fromFirstEvent(event, HealthStatus.NORMAL, T0.plusMillis(40));

        assertThat(state.machineId()).isEqualTo(DomainFixtures.MACHINE_ID);
        assertThat(state.latestReadings().temperatureCelsius()).isEqualTo(72.0);
        assertThat(state.lastTelemetryAt()).isEqualTo(T0);
        assertThat(state.lastUpdatedAt()).isEqualTo(T0.plusMillis(40));
    }

    @Nested
    class ApplyingEvents {

        @Test
        void shouldReplaceReadingsWithNewerEvent() {
            MachineState updated = stateAt(T0).apply(
                    DomainFixtures.eventAt(T0.plusSeconds(1), DomainFixtures.readingsWithTemperature(88.0)),
                    HealthStatus.WARNING,
                    T0.plusSeconds(1));

            assertThat(updated.latestReadings().temperatureCelsius()).isEqualTo(88.0);
            assertThat(updated.healthStatus()).isEqualTo(HealthStatus.WARNING);
            assertThat(updated.lastTelemetryAt()).isEqualTo(T0.plusSeconds(1));
        }

        /**
         * Kafka orders events within a partition, but a retry or a replay from an earlier offset
         * can still redeliver an old sample. Letting it through would overwrite current readings
         * with stale ones — a worse outcome than dropping it.
         */
        @Test
        void shouldIgnoreEventOlderThanCurrentState() {
            MachineState current = stateAt(T0.plusSeconds(10));

            MachineState result = current.apply(
                    DomainFixtures.eventAt(T0, DomainFixtures.readingsWithTemperature(99.0)),
                    HealthStatus.CRITICAL,
                    T0.plusSeconds(11));

            assertThat(result).isSameAs(current);
            assertThat(result.healthStatus()).isEqualTo(HealthStatus.NORMAL);
        }

        /** A redelivery carries the same timestamp, so "not strictly newer" must also be ignored. */
        @Test
        void shouldIgnoreEventWithSameTimestampAsCurrentState() {
            MachineState current = stateAt(T0);

            MachineState result = current.apply(
                    DomainFixtures.eventAt(T0, DomainFixtures.readingsWithTemperature(99.0)),
                    HealthStatus.CRITICAL,
                    T0.plusSeconds(1));

            assertThat(result).isSameAs(current);
        }

        @Test
        void shouldRejectEventFromAnotherMachine() {
            MachineState current = stateAt(T0);
            TelemetryEvent foreign = new TelemetryEvent(
                    UUID.randomUUID(), UUID.randomUUID(), T0.plusSeconds(5), DomainFixtures.nominalReadings());

            assertThatThrownBy(() -> current.apply(foreign, HealthStatus.NORMAL, T0.plusSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("belongs to machine");
        }

        @Test
        void shouldLeaveThePreviousStateUnchanged() {
            MachineState original = stateAt(T0);

            original.apply(DomainFixtures.eventAt(T0.plusSeconds(1), DomainFixtures.readingsWithTemperature(88.0)),
                    HealthStatus.CRITICAL, T0.plusSeconds(1));

            assertThat(original.healthStatus()).isEqualTo(HealthStatus.NORMAL);
            assertThat(original.lastTelemetryAt()).isEqualTo(T0);
        }
    }

    @Nested
    class ConnectivityDerivation {

        @Test
        void shouldBeOnlineImmediatelyAfterReporting() {
            assertThat(stateAt(T0).connectivityAt(T0, OFFLINE_AFTER)).isEqualTo(ConnectivityStatus.ONLINE);
        }

        @Test
        void shouldStillBeOnlineExactlyAtTheThreshold() {
            assertThat(stateAt(T0).connectivityAt(T0.plusSeconds(30), OFFLINE_AFTER))
                    .isEqualTo(ConnectivityStatus.ONLINE);
        }

        @Test
        void shouldBeOfflineOnceSilenceExceedsTheThreshold() {
            assertThat(stateAt(T0).connectivityAt(T0.plusSeconds(31), OFFLINE_AFTER))
                    .isEqualTo(ConnectivityStatus.OFFLINE);
        }

        /**
         * The same stored state yields a different answer as time passes — which is exactly why
         * connectivity is derived rather than persisted.
         */
        @Test
        void shouldYieldDifferentAnswersForTheSameStateAsTimePasses() {
            MachineState state = stateAt(T0);

            assertThat(state.connectivityAt(T0.plusSeconds(5), OFFLINE_AFTER))
                    .isEqualTo(ConnectivityStatus.ONLINE);
            assertThat(state.connectivityAt(T0.plusSeconds(500), OFFLINE_AFTER))
                    .isEqualTo(ConnectivityStatus.OFFLINE);
        }

        @Test
        void shouldTreatHealthAsIndependentOfConnectivity() {
            MachineState critical = MachineState.fromFirstEvent(
                    DomainFixtures.eventAt(T0, DomainFixtures.readingsWithTemperature(99.0)),
                    HealthStatus.CRITICAL, T0);

            assertThat(critical.connectivityAt(T0.plusSeconds(600), OFFLINE_AFTER))
                    .isEqualTo(ConnectivityStatus.OFFLINE);
            assertThat(critical.healthStatus()).isEqualTo(HealthStatus.CRITICAL);
        }
    }
}
