package com.sentinel.alert.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A detected abnormal condition on a machine, together with where it stands in its lifecycle.
 *
 * <p>Not a mutable status holder. The status can only move through {@link #acknowledge} and
 * {@link #resolve}, which is what keeps it consistent with the three timestamps: any code path
 * that could set {@code status = RESOLVED} without setting {@code resolvedAt} would produce a
 * record nobody can interpret afterwards. The compact constructor re-checks those invariants, so
 * an inconsistent {@code Alert} cannot be built by any route, including deserialisation later on.
 *
 * <p>Transitions return a new instance rather than mutating. Beyond thread-safety, this means the
 * pre-transition value is still available to the caller — useful when the update has to be
 * persisted conditionally and the previous status is needed to detect a lost race.
 *
 * <p>Timestamps are passed in, never read from the system clock, so lifecycle behaviour is
 * testable without freezing time globally.
 */
public record Alert(
        UUID id,
        UUID machineId,
        AlertType type,
        AlertSeverity severity,
        AlertStatus status,
        String message,
        Instant triggeredAt,
        /* nullable, set exactly when status is ACKNOWLEDGED or was acknowledged before resolution */
        Instant acknowledgedAt,
        /* nullable, set exactly when status is RESOLVED */
        Instant resolvedAt) {

    public Alert {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(machineId, "machineId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(triggeredAt, "triggeredAt must not be null");

        message = message.trim();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("message must not be blank");
        }

        switch (status) {
            case ACTIVE -> {
                requireAbsent(acknowledgedAt, "acknowledgedAt", status);
                requireAbsent(resolvedAt, "resolvedAt", status);
            }
            case ACKNOWLEDGED -> {
                requirePresent(acknowledgedAt, "acknowledgedAt", status);
                requireAbsent(resolvedAt, "resolvedAt", status);
            }
            // acknowledgedAt stays null when a condition cleared before anyone looked at it.
            case RESOLVED -> requirePresent(resolvedAt, "resolvedAt", status);
        }

        requireNotBefore(acknowledgedAt, triggeredAt, "acknowledgedAt");
        requireNotBefore(resolvedAt, triggeredAt, "resolvedAt");
        requireNotBefore(resolvedAt, acknowledgedAt, "resolvedAt");
    }

    /** Opens a new alert in {@link AlertStatus#ACTIVE}. */
    public static Alert raise(
            UUID id,
            UUID machineId,
            AlertType type,
            AlertSeverity severity,
            String message,
            Instant triggeredAt) {
        return new Alert(id, machineId, type, severity, AlertStatus.ACTIVE, message, triggeredAt, null, null);
    }

    /**
     * Takes ownership of an active alert.
     *
     * <p>Only {@code ACTIVE} may be acknowledged. Acknowledging twice is refused rather than
     * treated as a no-op: the second operator would otherwise be told they took ownership when
     * someone else already had it, and the recorded {@code acknowledgedAt} would not be theirs.
     *
     * @throws InvalidAlertTransitionException if the alert is already acknowledged or resolved
     */
    public Alert acknowledge(Instant acknowledgedAt) {
        Objects.requireNonNull(acknowledgedAt, "acknowledgedAt must not be null");
        if (status != AlertStatus.ACTIVE) {
            throw new InvalidAlertTransitionException(status, "acknowledge");
        }
        return new Alert(id, machineId, type, severity, AlertStatus.ACKNOWLEDGED, message,
                triggeredAt, acknowledgedAt, null);
    }

    /**
     * Closes the alert because the condition no longer holds.
     *
     * <p>Reachable from both {@code ACTIVE} and {@code ACKNOWLEDGED}. Resolving an already
     * resolved alert is refused: {@code RESOLVED} is terminal, and quietly overwriting
     * {@code resolvedAt} would move the recorded end of an incident that was already closed.
     * Callers driving this from rule evaluation should test {@link #isOpen()} first rather than
     * relying on the exception for flow control.
     *
     * @throws InvalidAlertTransitionException if the alert is already resolved
     */
    public Alert resolve(Instant resolvedAt) {
        Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        if (status == AlertStatus.RESOLVED) {
            throw new InvalidAlertTransitionException(status, "resolve");
        }
        return new Alert(id, machineId, type, severity, AlertStatus.RESOLVED, message,
                triggeredAt, acknowledgedAt, resolvedAt);
    }

    /** Whether this alert still represents a live condition. */
    public boolean isOpen() {
        return status.isOpen();
    }

    private static void requirePresent(Instant value, String field, AlertStatus status) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must be set when status is " + status);
        }
    }

    private static void requireAbsent(Instant value, String field, AlertStatus status) {
        if (value != null) {
            throw new IllegalArgumentException(field + " must not be set when status is " + status);
        }
    }

    private static void requireNotBefore(Instant value, Instant lowerBound, String field) {
        if (value != null && lowerBound != null && value.isBefore(lowerBound)) {
            throw new IllegalArgumentException(field + " must not precede " + lowerBound);
        }
    }
}
