package com.sentinel.rule.domain;

import java.util.Objects;

import com.sentinel.alert.domain.AlertSeverity;
import com.sentinel.alert.domain.AlertType;

/**
 * The outcome of evaluating one rule against one context.
 *
 * <p>Sealed with exactly two cases so that "no finding" is a value with a name rather than a
 * {@code null} every caller must remember to check, and so that a {@code switch} over the result
 * is checked for exhaustiveness by the compiler.
 *
 * <p>A rule reports a finding; it does not create an {@link com.sentinel.alert.domain.Alert}.
 * Producing an alert requires an identity and a decision about whether this condition is already
 * being reported — deduplication and cooldown state that the rule has no access to and should not
 * acquire. Keeping the result purely descriptive is what lets the same rules be reused later by
 * an engine that does hold that state (M6), and what keeps them trivially unit-testable now.
 */
public sealed interface RuleResult {

    /** The condition the rule watches for is present. */
    record Triggered(AlertType alertType, AlertSeverity severity, String message) implements RuleResult {

        public Triggered {
            Objects.requireNonNull(alertType, "alertType must not be null");
            Objects.requireNonNull(severity, "severity must not be null");
            Objects.requireNonNull(message, "message must not be null");
            if (message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }
    }

    /** The condition is absent. Stateless, so a single instance is shared. */
    record NotTriggered() implements RuleResult {

        private static final NotTriggered INSTANCE = new NotTriggered();
    }

    static RuleResult triggered(AlertType alertType, AlertSeverity severity, String message) {
        return new Triggered(alertType, severity, message);
    }

    static RuleResult notTriggered() {
        return NotTriggered.INSTANCE;
    }
}
