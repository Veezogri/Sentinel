package com.sentinel.simulation.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import com.sentinel.infrastructure.kafka.KafkaTopics;
import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.machine.application.MachineStateStore;
import com.sentinel.machine.domain.HealthStatus;
import com.sentinel.simulation.anomaly.AnomalyType;
import com.sentinel.telemetry.infrastructure.messaging.TelemetryPublisher;

/**
 * The complete runtime path, driven by the scheduler rather than by a test publishing by hand:
 *
 * <pre>
 *   SimulationRunner → TelemetryPublisher → Kafka → TelemetryListener
 *                    → TelemetryMessageMapper → TelemetryProcessor
 *                    → MachineStateStore + RuleEngine
 * </pre>
 *
 * <p>This is the milestone's proof that the pipeline runs, not merely that its parts compose. The
 * simulation is enabled here and nowhere else in the test suite, precisely because background
 * traffic would make every other test race against a fleet of machines.
 */
@SpringBootTest(properties = {
        "sentinel.simulation.enabled=true",
        "sentinel.simulation.machine-count=10",
        "sentinel.simulation.tick-interval=100ms",
        "sentinel.simulation.anomaly-probability=0.0",
        "sentinel.simulation.seed=2026"
})
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = KafkaTopics.TELEMETRY_RAW_PARTITIONS,
        topics = {KafkaTopics.TELEMETRY_RAW, KafkaTopics.DEAD_LETTER},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class SimulationPipelineEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(SimulationPipelineEndToEndTest.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MACHINE_COUNT = 10;

    @Autowired
    private SimulationRunner runner;

    @Autowired
    private TelemetryPublisher publisher;

    @Autowired
    private MachineStateStore stateStore;

    @Autowired
    private MachineRegistry machineRegistry;

    @Test
    void shouldCarrySimulatedTelemetryOfEveryMachineThroughToState() {
        assertThat(machineRegistry.size())
                .as("the runner announces its fleet at startup")
                .isEqualTo(MACHINE_COUNT);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.size())
                        .as("every simulated machine should reach the state store")
                        .isEqualTo(MACHINE_COUNT));

        // Sends are asynchronous, so reading the counters the instant the state store fills would
        // show acknowledged trailing scheduled — in-flight work that is easily misread as loss.
        // Wait for the producer to drain first, then every number means one thing.
        await().atMost(TIMEOUT).until(() -> publisher.pendingCount() == 0);

        long scheduled = publisher.scheduledCount();
        log.info("PIPELINE PROOF | scheduled={} acknowledged={} failed={} pending={} machinesWithState={}",
                scheduled, publisher.acknowledgedCount(), publisher.failedCount(),
                publisher.pendingCount(), stateStore.size());

        assertThat(scheduled).isGreaterThanOrEqualTo(MACHINE_COUNT);
        assertThat(publisher.acknowledgedCount())
                .as("once drained, every scheduled send is either acknowledged or failed")
                .isEqualTo(scheduled);
        assertThat(publisher.failedCount()).isZero();
    }

    @Test
    void shouldReachCriticalHealthThroughTheRunningPipelineWhenAMachineOverheats() {
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.size()).isEqualTo(MACHINE_COUNT));

        UUID machineId = runner.engine().machines().get(0).id();
        // Six seconds of *simulated* time. With a 100 ms tick that is sixty rounds, so the fault
        // ramps up and holds well within the test's patience; a two-minute fault would take two
        // real minutes here, because the runner deliberately advances one simulated tick per
        // wall-clock tick when running live.
        runner.engine().triggerAnomaly(machineId, AnomalyType.OVERHEATING, Duration.ofSeconds(6));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state ->
                        assertThat(state.healthStatus()).isEqualTo(HealthStatus.CRITICAL)));

        var peak = stateStore.find(machineId).orElseThrow();
        log.info("PIPELINE PROOF | machine={} temperature={} health={}",
                machineId, peak.latestReadings().temperatureCelsius(), peak.healthStatus());
    }

    /** A machine in communication loss stops producing, so its state simply stops moving. */
    @Test
    void shouldStopAdvancingStateForASilencedMachine() {
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.size()).isEqualTo(MACHINE_COUNT));

        UUID machineId = runner.engine().machines().get(1).id();
        runner.engine().triggerAnomaly(machineId, AnomalyType.COMMUNICATION_LOSS, Duration.ofSeconds(600));

        await().atMost(Duration.ofSeconds(10)).until(() ->
                stateStore.find(machineId).isPresent());
        var lastHeard = stateStore.find(machineId).orElseThrow().lastTelemetryAt();

        await().during(Duration.ofSeconds(3)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId).orElseThrow().lastTelemetryAt())
                        .as("a silent machine produces nothing, so its state stops advancing")
                        .isEqualTo(lastHeard));
    }
}
