package com.sentinel.alert.domain;

/**
 * Raised when a lifecycle transition is refused, for example acknowledging an already resolved
 * alert.
 *
 * <p>Refusing rather than silently ignoring is a deliberate choice. These conflicts are real
 * concurrency outcomes — two operators acknowledging at once, or an operator acknowledging just
 * as the condition clears and the engine resolves the alert. Treating the loser as a success
 * would report an action that never happened; surfacing it lets the API answer {@code 409
 * Conflict} and lets the caller re-read the current state.
 */
public class InvalidAlertTransitionException extends RuntimeException {

    public InvalidAlertTransitionException(AlertStatus from, String action) {
        super("cannot " + action + " an alert in status " + from);
    }
}
