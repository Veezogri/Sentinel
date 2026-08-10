package com.sentinel.alert.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AlertTest {

    private static final UUID ALERT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MACHINE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant TRIGGERED_AT = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant LATER = TRIGGERED_AT.plusSeconds(60);
    private static final Instant EVEN_LATER = TRIGGERED_AT.plusSeconds(120);

    private static Alert activeAlert() {
        return Alert.raise(ALERT_ID, MACHINE_ID, AlertType.HIGH_TEMPERATURE, AlertSeverity.CRITICAL,
                "Temperature 96.0 °C reached the critical threshold of 95.0 °C", TRIGGERED_AT);
    }

    @Test
    void shouldRaiseAlertAsActiveWithNoLifecycleTimestamps() {
        Alert alert = activeAlert();

        assertThat(alert.status()).isEqualTo(AlertStatus.ACTIVE);
        assertThat(alert.triggeredAt()).isEqualTo(TRIGGERED_AT);
        assertThat(alert.acknowledgedAt()).isNull();
        assertThat(alert.resolvedAt()).isNull();
        assertThat(alert.isOpen()).isTrue();
    }

    @Test
    void shouldRejectBlankMessage() {
        assertThatThrownBy(() -> Alert.raise(ALERT_ID, MACHINE_ID, AlertType.HIGH_TEMPERATURE,
                AlertSeverity.WARNING, "   ", TRIGGERED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message");
    }

    @Nested
    class Acknowledgment {

        @Test
        void shouldMoveActiveAlertToAcknowledged() {
            Alert acknowledged = activeAlert().acknowledge(LATER);

            assertThat(acknowledged.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
            assertThat(acknowledged.acknowledgedAt()).isEqualTo(LATER);
            assertThat(acknowledged.resolvedAt()).isNull();
            assertThat(acknowledged.isOpen()).isTrue();
        }

        @Test
        void shouldLeaveOriginalAlertUntouched() {
            Alert alert = activeAlert();

            alert.acknowledge(LATER);

            assertThat(alert.status()).isEqualTo(AlertStatus.ACTIVE);
            assertThat(alert.acknowledgedAt()).isNull();
        }

        /**
         * Refused rather than ignored: the second operator would otherwise be told they took
         * ownership, while the recorded timestamp remains the first operator's.
         */
        @Test
        void shouldRejectAcknowledgingTwice() {
            Alert acknowledged = activeAlert().acknowledge(LATER);

            assertThatThrownBy(() -> acknowledged.acknowledge(EVEN_LATER))
                    .isInstanceOf(InvalidAlertTransitionException.class)
                    .hasMessageContaining("ACKNOWLEDGED");
        }

        @Test
        void shouldRejectAcknowledgingResolvedAlert() {
            Alert resolved = activeAlert().resolve(LATER);

            assertThatThrownBy(() -> resolved.acknowledge(EVEN_LATER))
                    .isInstanceOf(InvalidAlertTransitionException.class)
                    .hasMessageContaining("RESOLVED");
        }
    }

    @Nested
    class Resolution {

        /** A condition that clears before anyone looked at it is a normal outcome. */
        @Test
        void shouldResolveDirectlyFromActiveWithoutAcknowledgment() {
            Alert resolved = activeAlert().resolve(LATER);

            assertThat(resolved.status()).isEqualTo(AlertStatus.RESOLVED);
            assertThat(resolved.resolvedAt()).isEqualTo(LATER);
            assertThat(resolved.acknowledgedAt()).isNull();
            assertThat(resolved.isOpen()).isFalse();
        }

        @Test
        void shouldResolveFromAcknowledgedKeepingAcknowledgmentTime() {
            Alert resolved = activeAlert().acknowledge(LATER).resolve(EVEN_LATER);

            assertThat(resolved.status()).isEqualTo(AlertStatus.RESOLVED);
            assertThat(resolved.acknowledgedAt()).isEqualTo(LATER);
            assertThat(resolved.resolvedAt()).isEqualTo(EVEN_LATER);
        }

        /** RESOLVED is terminal: re-resolving would move the recorded end of a closed incident. */
        @Test
        void shouldRejectResolvingTwice() {
            Alert resolved = activeAlert().resolve(LATER);

            assertThatThrownBy(() -> resolved.resolve(EVEN_LATER))
                    .isInstanceOf(InvalidAlertTransitionException.class)
                    .hasMessageContaining("RESOLVED");
        }
    }

    @Nested
    class Invariants {

        @Test
        void shouldRejectAcknowledgedStatusWithoutTimestamp() {
            assertThatThrownBy(() -> new Alert(ALERT_ID, MACHINE_ID, AlertType.HIGH_TEMPERATURE,
                    AlertSeverity.WARNING, AlertStatus.ACKNOWLEDGED, "message", TRIGGERED_AT, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("acknowledgedAt");
        }

        @Test
        void shouldRejectResolvedStatusWithoutTimestamp() {
            assertThatThrownBy(() -> new Alert(ALERT_ID, MACHINE_ID, AlertType.HIGH_TEMPERATURE,
                    AlertSeverity.WARNING, AlertStatus.RESOLVED, "message", TRIGGERED_AT, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resolvedAt");
        }

        @Test
        void shouldRejectActiveStatusCarryingResolutionTimestamp() {
            assertThatThrownBy(() -> new Alert(ALERT_ID, MACHINE_ID, AlertType.HIGH_TEMPERATURE,
                    AlertSeverity.WARNING, AlertStatus.ACTIVE, "message", TRIGGERED_AT, null, LATER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resolvedAt");
        }

        @Test
        void shouldRejectResolutionBeforeTrigger() {
            assertThatThrownBy(() -> new Alert(ALERT_ID, MACHINE_ID, AlertType.HIGH_TEMPERATURE,
                    AlertSeverity.WARNING, AlertStatus.RESOLVED, "message", TRIGGERED_AT, null,
                    TRIGGERED_AT.minusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resolvedAt");
        }

        @Test
        void shouldRejectResolutionBeforeAcknowledgment() {
            assertThatThrownBy(() -> new Alert(ALERT_ID, MACHINE_ID, AlertType.HIGH_TEMPERATURE,
                    AlertSeverity.WARNING, AlertStatus.RESOLVED, "message", TRIGGERED_AT, EVEN_LATER, LATER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resolvedAt");
        }
    }
}
