package com.sentinel.infrastructure.kafka;

/**
 * Topic names and their local layout, declared once.
 *
 * <p>A topic name repeated as a string literal across producer, consumer, tests and error handler
 * is a rename waiting to go wrong: the compiler cannot help, and a mismatch shows up as silence
 * rather than as a failure.
 */
public final class KafkaTopics {

    /** Raw telemetry as produced by machines, keyed by {@code machineId}. */
    public static final String TELEMETRY_RAW = "sentinel.telemetry.raw";

    /** Terminal destination for records that could not be processed. */
    public static final String DEAD_LETTER = "sentinel.dead-letter";

    /**
     * Partitions on {@code sentinel.telemetry.raw}.
     *
     * <p>Six is a starting point for local development, not a derived figure. It is deliberately
     * <em>not</em> tied to the machine count, the number of machine types or the CPU count: it is
     * simply enough to run several consumers concurrently and to watch a group rebalance, while
     * staying cheap on one broker.
     *
     * <p>It sets the ceiling on consumer parallelism — a partition is read by at most one member
     * of a group at a time, so a seventh consumer thread would sit idle. Raising it later is
     * possible; lowering it is not, and repartitioning changes which partition a key lands on,
     * which is why the number deserves a measurement before it is treated as final.
     */
    public static final int TELEMETRY_RAW_PARTITIONS = 6;

    /**
     * A single partition is enough for the dead-letter topic: it is low volume, and keeping
     * failures in one place makes them easier to read through.
     */
    public static final int DEAD_LETTER_PARTITIONS = 1;

    /**
     * One broker means one copy. This is a development topology with no redundancy: losing the
     * broker loses the data. A production cluster would use at least three brokers with
     * {@code replication.factor=3} and {@code min.insync.replicas=2}.
     */
    public static final short LOCAL_REPLICATION_FACTOR = 1;

    private KafkaTopics() {
    }
}
