package com.sentinel.alert.domain;

/**
 * Where an alert sits in its lifecycle.
 *
 * <pre>
 *   ACTIVE ──acknowledge──▶ ACKNOWLEDGED
 *      │                        │
 *      └────────resolve─────────┴──────▶ RESOLVED   (terminal)
 * </pre>
 *
 * <p>{@code ACTIVE → RESOLVED} skips acknowledgment on purpose: a condition that clears on its
 * own before anyone looked at it is a normal outcome, not an anomaly to be forced through an
 * operator action.
 *
 * <p>The transitions themselves are enforced by {@link Alert}, which is the only place that can
 * keep the status and its timestamps consistent.
 */
public enum AlertStatus {

    /** Detected and open; nobody has taken ownership. */
    ACTIVE,

    /** An operator has seen it and taken ownership; the condition still holds. */
    ACKNOWLEDGED,

    /** The condition no longer holds. Terminal. */
    RESOLVED;

    /** Whether the alert still represents a live condition. */
    public boolean isOpen() {
        return this != RESOLVED;
    }
}
