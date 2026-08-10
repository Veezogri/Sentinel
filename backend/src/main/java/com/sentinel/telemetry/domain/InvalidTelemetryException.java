package com.sentinel.telemetry.domain;

/**
 * Raised when telemetry is <em>structurally</em> impossible — a missing identifier, a negative
 * rotation speed, a temperature below absolute zero.
 *
 * <p>It must never be raised for a reading that is merely alarming. A temperature of 140 °C is a
 * perfectly well-formed measurement describing a machine that is about to fail; rejecting it
 * would discard exactly the data the platform exists to detect. Only corruption is rejected here,
 * and abnormality is left to the rule engine.
 *
 * <p>Unchecked on purpose: at runtime this is a bad-message condition, handled once at the
 * ingestion boundary by routing to the dead-letter topic (M13), not by a caller that could
 * meaningfully recover.
 */
public class InvalidTelemetryException extends RuntimeException {

    public InvalidTelemetryException(String message) {
        super(message);
    }
}
