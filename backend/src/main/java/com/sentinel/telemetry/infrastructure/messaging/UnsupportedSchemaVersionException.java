package com.sentinel.telemetry.infrastructure.messaging;

/**
 * Raised when a message announces a schema version this build does not know how to read.
 *
 * <p>Its own type rather than a generic failure, because it is <em>not retryable</em>: replaying
 * the same bytes will produce the same answer forever. The error handler uses that distinction to
 * send the record straight to the dead-letter topic instead of burning a retry budget on it.
 */
public class UnsupportedSchemaVersionException extends RuntimeException {

    public UnsupportedSchemaVersionException(int received, int supported) {
        super("unsupported telemetry schema version " + received + ", this build reads version " + supported);
    }
}
