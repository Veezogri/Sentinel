package com.sentinel.telemetry.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sentinel.infrastructure.kafka.KafkaTopics;
import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.machine.application.MachineStateStore;
import com.sentinel.machine.domain.Machine;
import com.sentinel.machine.domain.MachineType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;
import com.sentinel.testsupport.KafkaIntegrationTestBase;

/**
 * The partitioning contract, verified against the broker rather than assumed.
 *
 * <p>Ordering in this platform rests entirely on one property: all events of a machine share a
 * partition. Kafka orders records within a partition and promises nothing across partitions, so
 * if the key were ever dropped or changed, per-machine ordering would silently disappear — no
 * exception, no failing unit test, just occasional stale state under load.
 */
class TelemetryPartitioningIT extends KafkaIntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private TelemetryPublisher publisher;

    @Autowired
    private MachineStateStore stateStore;

    @Autowired
    private MachineRegistry machineRegistry;

    private UUID machineId;

    @BeforeEach
    void registerMachine() {
        machineId = UUID.randomUUID();
        machineRegistry.register(
                Machine.register(machineId, "PUMP-PART", MachineType.PUMP, Instant.now()));
    }

    private TelemetryEvent event(UUID machine, Instant occurredAt) {
        return new TelemetryEvent(UUID.randomUUID(), machine, occurredAt,
                new TelemetryReadings(62.0, 2.0, 5.0, 30.0, 1400.0));
    }

    @Test
    void shouldCreateTheTopicWithTheConfiguredPartitionsAndReplication() throws Exception {
        TopicDescription description = describeTopic(KafkaTopics.TELEMETRY_RAW);

        assertThat(description.partitions()).hasSize(KafkaTopics.TELEMETRY_RAW_PARTITIONS);
        assertThat(description.partitions().get(0).replicas())
                .hasSize(KafkaTopics.LOCAL_REPLICATION_FACTOR);
    }

    @Test
    void shouldCreateTheDeadLetterTopic() throws Exception {
        assertThat(describeTopic(KafkaTopics.DEAD_LETTER).partitions())
                .hasSize(KafkaTopics.DEAD_LETTER_PARTITIONS);
    }

    /** The core guarantee: one machine, one partition, whatever the volume. */
    @Test
    void shouldRouteEveryEventOfOneMachineToASinglePartition() {
        Instant start = Instant.parse("2026-01-15T13:00:00Z");
        for (int i = 0; i < 30; i++) {
            publisher.publish(event(machineId, start.plusSeconds(i)));
        }

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).isPresent());

        Set<Integer> partitions = partitionsUsedByKey(machineId.toString());

        assertThat(partitions)
                .as("all events of a machine must share one partition")
                .hasSize(1);
    }

    /**
     * Several machines are expected to spread over the topic. Asserting that two <em>specific</em>
     * identifiers land on different partitions would be a coin flip, so this uses enough machines
     * that a single-partition outcome would mean the key is being ignored altogether.
     */
    @Test
    void shouldSpreadDifferentMachinesAcrossPartitions() {
        Instant occurredAt = Instant.parse("2026-01-15T14:00:00Z");
        List<UUID> machines = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        machines.forEach(id -> {
            machineRegistry.register(Machine.register(id, "M-" + id, MachineType.MOTOR, Instant.now()));
            publisher.publish(event(id, occurredAt));
        });

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machines.get(machines.size() - 1))).isPresent());

        Set<Integer> used = new HashSet<>();
        for (ConsumerRecord<String, String> record : drainTopic(KafkaTopics.TELEMETRY_RAW)) {
            used.add(record.partition());
        }

        assertThat(used).as("40 machines should not all hash to one partition").hasSizeGreaterThan(1);
    }

    private Set<Integer> partitionsUsedByKey(String key) {
        Set<Integer> partitions = new HashSet<>();
        for (ConsumerRecord<String, String> record : drainTopic(KafkaTopics.TELEMETRY_RAW)) {
            if (key.equals(record.key())) {
                partitions.add(record.partition());
            }
        }
        return partitions;
    }

    private List<ConsumerRecord<String, String>> drainTopic(String topic) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "inspector-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            for (int attempt = 0; attempt < 5; attempt++) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(2));
                polled.forEach(collected::add);
            }
        }
        return collected;
    }

    private TopicDescription describeTopic(String topic) throws InterruptedException, ExecutionException {
        Map<String, Object> config =
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        try (AdminClient admin = AdminClient.create(config)) {
            return admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic);
        }
    }
}
