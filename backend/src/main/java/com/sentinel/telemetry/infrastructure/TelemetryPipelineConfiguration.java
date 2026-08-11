package com.sentinel.telemetry.infrastructure;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.machine.application.MachineStateStore;
import com.sentinel.rule.domain.RuleEngine;
import com.sentinel.telemetry.application.TelemetryProcessor;
import com.sentinel.telemetry.infrastructure.messaging.TelemetryListener;
import com.sentinel.telemetry.infrastructure.messaging.TelemetryMessageMapper;
import com.sentinel.telemetry.infrastructure.messaging.TelemetryPublisher;

/**
 * Wires the ingestion pipeline.
 *
 * <p>Constructor injection through explicit {@code @Bean} methods rather than component scanning,
 * so that the shape of the pipeline is readable in one file: mapper, publisher, processor,
 * listener, and what each depends on.
 */
@Configuration
public class TelemetryPipelineConfiguration {

    @Bean
    TelemetryMessageMapper telemetryMessageMapper() {
        return new TelemetryMessageMapper();
    }

    @Bean
    TelemetryPublisher telemetryPublisher(
            KafkaTemplate<String, Object> kafkaTemplate, TelemetryMessageMapper mapper) {
        return new TelemetryPublisher(kafkaTemplate, mapper);
    }

    @Bean
    TelemetryProcessor telemetryProcessor(
            MachineStateStore stateStore,
            MachineRegistry machineRegistry,
            RuleEngine ruleEngine,
            Clock clock) {
        return new TelemetryProcessor(stateStore, machineRegistry, ruleEngine, clock);
    }

    @Bean
    TelemetryListener telemetryListener(TelemetryMessageMapper mapper, TelemetryProcessor processor) {
        return new TelemetryListener(mapper, processor);
    }
}
