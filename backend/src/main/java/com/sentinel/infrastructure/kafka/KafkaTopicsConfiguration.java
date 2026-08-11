package com.sentinel.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics the application needs.
 *
 * <p>Spring's {@code KafkaAdmin} creates any missing topic from these beans at startup. That was
 * chosen over an init script or a separate container for one reason: the topic layout is part of
 * the application's contract, so it belongs in the same repository, reviewed in the same commit
 * and applied identically on a laptop, in a Testcontainers run and in CI. A shell script would
 * have to be invoked separately and would drift; an init container would only cover Compose and
 * leave the integration tests to define topics some other way.
 *
 * <p>Broker-side auto-creation stays disabled. Partition count and retention are design
 * decisions, and a topic that springs into existence with defaults because someone typed its name
 * slightly wrong is a failure that presents as silence.
 *
 * <p>The limit of this approach: {@code KafkaAdmin} only ever <em>creates</em>. It will not
 * repartition or alter an existing topic, so changing {@link KafkaTopics#TELEMETRY_RAW_PARTITIONS}
 * has no effect on a topic that already exists. In production this provisioning would move to
 * infrastructure-as-code rather than to the application's startup path.
 */
@Configuration
public class KafkaTopicsConfiguration {

    @Bean
    NewTopic telemetryRawTopic() {
        return TopicBuilder.name(KafkaTopics.TELEMETRY_RAW)
                .partitions(KafkaTopics.TELEMETRY_RAW_PARTITIONS)
                .replicas(KafkaTopics.LOCAL_REPLICATION_FACTOR)
                .build();
    }

    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(KafkaTopics.DEAD_LETTER)
                .partitions(KafkaTopics.DEAD_LETTER_PARTITIONS)
                .replicas(KafkaTopics.LOCAL_REPLICATION_FACTOR)
                .build();
    }
}
