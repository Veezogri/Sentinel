package com.sentinel.telemetry.infrastructure.messaging;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;

import com.sentinel.telemetry.application.TelemetryProcessingResult;
import com.sentinel.telemetry.application.TelemetryProcessor;
import com.sentinel.telemetry.domain.TelemetryEvent;

/**
 * The Kafka entry point for telemetry.
 *
 * <p>Intentionally thin: it maps the wire message to the domain and hands off. No business rule
 * lives here, because everything written inside a {@code @KafkaListener} can only be tested with
 * a broker running — and because the processing pipeline should not care that its input arrived
 * over Kafka.
 *
 * <p>Exceptions are allowed to propagate rather than being caught. The listener container's error
 * handler is what implements retry and dead-lettering, and a {@code catch} here would swallow the
 * failure, commit the offset and lose the record.
 *
 * <p>A record listener rather than a batch listener, deliberately. Batching would improve
 * throughput but changes the meaning of a failure — one bad record in a batch of five hundred
 * makes "retry the batch" and "dead-letter the batch" both wrong. Ordering, retry and failure
 * semantics get to be simple first; batching is an optimisation to evaluate against a measurement.
 */
public class TelemetryListener {

    private static final Logger log = LoggerFactory.getLogger(TelemetryListener.class);

    private final TelemetryMessageMapper mapper;
    private final TelemetryProcessor processor;

    public TelemetryListener(TelemetryMessageMapper mapper, TelemetryProcessor processor) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.processor = Objects.requireNonNull(processor, "processor must not be null");
    }

    @KafkaListener(
            topics = "#{T(com.sentinel.infrastructure.kafka.KafkaTopics).TELEMETRY_RAW}",
            groupId = "${sentinel.kafka.consumer-group}",
            concurrency = "${sentinel.kafka.concurrency}")
    public void onTelemetry(
            @Payload TelemetryMessage message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        TelemetryEvent event = mapper.toDomain(message);

        // DEBUG, not INFO: at a thousand events a second, one line per record is not a log, it is
        // a denial of service against whoever has to read it. Findings and failures log higher up.
        log.debug("consumed event {} for machine {} from {}-{}@{}",
                event.eventId(), event.machineId(), topic, partition, offset);

        TelemetryProcessingResult result = processor.process(event);

        if (result.hasFindings()) {
            log.debug("event {} produced {} finding(s)", event.eventId(), result.findings().size());
        }
    }
}
