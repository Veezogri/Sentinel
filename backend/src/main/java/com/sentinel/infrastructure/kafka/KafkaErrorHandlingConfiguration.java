package com.sentinel.infrastructure.kafka;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

import com.sentinel.telemetry.domain.InvalidTelemetryException;
import com.sentinel.telemetry.infrastructure.messaging.UnsupportedSchemaVersionException;

/**
 * What happens when a record cannot be processed.
 *
 * <h2>Three kinds of failure, two treatments</h2>
 * <ul>
 *   <li><strong>Malformed payload</strong> — the bytes are not a valid message. Surfaces as a
 *       {@link DeserializationException}.</li>
 *   <li><strong>Invalid domain content</strong> — well-formed JSON that cannot become a valid
 *       {@code TelemetryEvent}, or a schema version this build cannot read.</li>
 *   <li><strong>Processing failure</strong> — everything else: a bug, or a dependency that is
 *       temporarily unavailable.</li>
 * </ul>
 *
 * <p>The first two are <em>deterministic</em>: replaying the same bytes produces the same failure
 * forever, so retrying them wastes the retry budget and delays every record behind them on the
 * partition. They are classified as non-retryable and go straight to the dead-letter topic. Only
 * the third kind is retried, because only it can plausibly succeed on a second attempt.
 *
 * <p>This is the poison-pill problem: without that split, one bad record blocks its partition
 * indefinitely and the consumer makes no progress. Kafka does not let a consumer skip a record
 * and come back to it — the offset is a position, not a work queue — so the only way forward is
 * to move the record aside and commit past it.
 */
@Configuration
public class KafkaErrorHandlingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfiguration.class);

    /** Bounded on purpose: 4 attempts in total, roughly 3.5 seconds, then the record is set aside. */
    private static final long INITIAL_BACKOFF_MS = 500L;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_BACKOFF_MS = 4_000L;
    private static final int MAX_ATTEMPTS = 4;

    /**
     * A byte-oriented template used only for dead-lettering.
     *
     * <p>When deserialisation is what failed, the recoverer republishes the <em>original bytes</em>
     * rather than a parsed object. Sending those through the JSON template would serialise a
     * {@code byte[]} as a base64 string, so the one thing a dead-lettered record exists for — being
     * readable by whoever investigates — would be lost.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            KafkaOperations<String, Object> kafkaOperations,
            KafkaTemplate<String, byte[]> deadLetterKafkaTemplate) {

        DefaultErrorHandler handler = new DefaultErrorHandler(
                deadLetterRecoverer(kafkaOperations, deadLetterKafkaTemplate), backOff());

        // Deterministic failures: no amount of retrying changes the outcome.
        handler.addNotRetryableExceptions(
                DeserializationException.class,
                ConversionException.class,
                UnsupportedSchemaVersionException.class,
                InvalidTelemetryException.class,
                IllegalArgumentException.class);

        // Called on every failed delivery, including the first, and including for exceptions that
        // will not be retried at all — so the message says "attempt failed", not "retrying".
        // A non-retryable failure produces exactly one of these lines before the record is
        // dead-lettered; a retryable one produces up to MAX_ATTEMPTS.
        handler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("delivery attempt {} (of at most {}) failed for {}-{} offset {}: {}",
                        deliveryAttempt, MAX_ATTEMPTS, record.topic(), record.partition(),
                        record.offset(), exception.toString()));

        return handler;
    }

    private DeadLetterPublishingRecoverer deadLetterRecoverer(
            KafkaOperations<String, Object> jsonTemplate,
            KafkaOperations<String, byte[]> byteTemplate) {

        // Ordered: a raw byte[] payload (a deserialisation failure) goes through the byte
        // template, anything else through the JSON one. Spring picks the first assignable key.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, byteTemplate);
        templates.put(Object.class, jsonTemplate);

        // Spring stamps the original topic, partition, offset, timestamp, exception class and
        // stack trace into headers, which is what makes a dead-lettered record diagnosable.
        return new DeadLetterPublishingRecoverer(
                templates,
                // Partition -1 lets the producer choose. The default resolver would reuse the
                // source partition number, which cannot work here: telemetry has six partitions
                // and the dead-letter topic has one, so records from partition 3 would be
                // published to a partition that does not exist.
                (record, exception) -> new TopicPartition(KafkaTopics.DEAD_LETTER, -1));
    }

    private ExponentialBackOff backOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_BACKOFF_MS, BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_BACKOFF_MS);
        // Never unbounded: an infinite retry on a permanently failing record is a stalled
        // partition that looks like a healthy consumer.
        backOff.setMaxAttempts(MAX_ATTEMPTS - 1);
        return backOff;
    }
}
