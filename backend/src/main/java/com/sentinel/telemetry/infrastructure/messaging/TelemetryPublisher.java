package com.sentinel.telemetry.infrastructure.messaging;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.sentinel.infrastructure.kafka.KafkaTopics;
import com.sentinel.telemetry.domain.TelemetryEvent;

/**
 * Publishes telemetry to Kafka.
 *
 * <p>The only class in the write path that knows Kafka exists. The simulator produces
 * {@link TelemetryEvent}s and knows nothing about topics or brokers; the runtime that drives it
 * depends on this class, not on {@code KafkaTemplate}.
 *
 * <h2>Partition key</h2>
 * The record key is {@code machineId}, so every event from one machine hashes to the same
 * partition and is therefore delivered in order relative to its siblings. Kafka orders records
 * within a partition and makes no promise across partitions, so this key is the whole ordering
 * guarantee — without it a machine's samples would scatter and a later reading could be processed
 * before an earlier one.
 *
 * <h2>Send failures are asynchronous</h2>
 * {@code send} returns immediately and the broker acknowledges later. Blocking on the returned
 * future would turn a batching, pipelined producer into a synchronous round trip per event and
 * cap throughput at one in-flight request — so the future is observed with a callback instead.
 * Not observing it at all would be worse than either: failures would vanish silently.
 */
public class TelemetryPublisher {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPublisher.class);

    /** Small enough to be worth duplicating: lets a consumer route or filter without parsing. */
    private static final String HEADER_SCHEMA_VERSION = "sentinel-schema-version";
    private static final String HEADER_EVENT_ID = "sentinel-event-id";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TelemetryMessageMapper mapper;

    // Three counters, not two. A send is handed to the producer long before the broker answers,
    // so "scheduled" and "acknowledged" are legitimately different numbers at any instant and the
    // gap between them is in-flight work, not loss. Reporting only a produced/published pair
    // invites reading that gap as dropped events.
    private final LongAdder scheduled = new LongAdder();
    private final LongAdder acknowledged = new LongAdder();
    private final LongAdder failed = new LongAdder();

    public TelemetryPublisher(KafkaTemplate<String, Object> kafkaTemplate, TelemetryMessageMapper mapper) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Sends one event, returning the in-flight send so a caller that genuinely needs completion
     * can wait on it. Callers that do not care can ignore it: failures are already logged and
     * counted here.
     */
    public CompletableFuture<SendResult<String, Object>> publish(TelemetryEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        TelemetryMessage message = mapper.toMessage(event);
        ProducerRecord<String, Object> record = new ProducerRecord<>(
                KafkaTopics.TELEMETRY_RAW, null, event.machineId().toString(), message);

        record.headers()
                .add(new RecordHeader(HEADER_SCHEMA_VERSION,
                        Integer.toString(message.schemaVersion()).getBytes()))
                .add(new RecordHeader(HEADER_EVENT_ID, event.eventId().toString().getBytes()));

        scheduled.increment();
        CompletableFuture<SendResult<String, Object>> sent = kafkaTemplate.send(record);
        sent.whenComplete((result, throwable) -> {
            if (throwable != null) {
                failed.increment();
                log.error("failed to publish event {} for machine {}",
                        event.eventId(), event.machineId(), throwable);
            } else {
                acknowledged.increment();
            }
        });
        return sent;
    }

    /** Sends handed to the producer. Counters, not metrics: Micrometer arrives in M14. */
    public long scheduledCount() {
        return scheduled.sum();
    }

    /** Sends the broker has acknowledged. */
    public long acknowledgedCount() {
        return acknowledged.sum();
    }

    /** Sends the broker rejected, or that exhausted the producer's delivery timeout. */
    public long failedCount() {
        return failed.sum();
    }

    /**
     * Sends handed over but not yet resolved either way.
     *
     * <p>Read from three separate counters, so the result is a close estimate rather than an
     * instantaneous truth — a send can complete between two of the reads. That is precise enough
     * to answer the only question asked of it: whether the producer has drained.
     */
    public long pendingCount() {
        return scheduled.sum() - acknowledged.sum() - failed.sum();
    }
}
