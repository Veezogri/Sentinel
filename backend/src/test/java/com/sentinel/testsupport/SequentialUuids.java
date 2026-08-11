package com.sentinel.testsupport;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Event identifiers that are reproducible across runs.
 *
 * <p>The simulator defaults to {@link UUID#randomUUID()} on purpose, so two producer processes
 * started with the same configuration cannot mint colliding identifiers. That makes generated
 * events differ between runs in exactly one field — which is fine everywhere except in a test
 * that compares two runs event for event. Such tests inject this instead.
 */
public final class SequentialUuids implements Supplier<UUID> {

    private long counter;

    @Override
    public UUID get() {
        return new UUID(0L, ++counter);
    }
}
