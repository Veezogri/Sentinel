package com.sentinel.infrastructure.kafka;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * All Kafka producer wiring, in one place.
 *
 * <p>Two producers exist: the JSON one that publishes telemetry, and a byte-oriented one used only
 * for dead-lettering. They are declared together and explicitly, rather than letting Spring Boot
 * auto-configure the first and adding the second beside it.
 *
 * <p>That is a deliberate response to how the auto-configuration is conditioned. Boot creates its
 * {@code ProducerFactory} and {@code KafkaTemplate} only when no bean of those types exists, so
 * introducing a single extra producer silently removes the default one — and the failure surfaces
 * as an unrelated bean two levels away failing to autowire. Declaring the whole chain makes the
 * wiring independent of that back-off, and readable in one file.
 *
 * <p>Both factories are beans so the container closes them on shutdown. A producer factory created
 * inline would keep its sender thread alive past context close, a leak that shows up only as a JVM
 * that will not exit.
 *
 * <p>Settings still come from {@code spring.kafka.*}; only the serialisers are overridden.
 */
@Configuration
public class KafkaProducerConfiguration {

    @Bean
    @Primary
    ProducerFactory<String, Object> producerFactory(
            KafkaProperties properties, ObjectProvider<SslBundles> sslBundles) {
        return new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(sslBundles.getIfAvailable()));
    }

    @Bean
    @Primary
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * When deserialisation is what failed, the recoverer republishes the <em>original bytes</em>
     * rather than a parsed object. Sending those through the JSON template would serialise a
     * {@code byte[]} as a base64 string, so the one thing a dead-lettered record exists for —
     * being readable by whoever investigates — would be lost.
     */
    @Bean
    ProducerFactory<String, byte[]> deadLetterProducerFactory(
            KafkaProperties properties, ObjectProvider<SslBundles> sslBundles) {

        Map<String, Object> config =
                new HashMap<>(properties.buildProducerProperties(sslBundles.getIfAvailable()));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    KafkaTemplate<String, byte[]> deadLetterKafkaTemplate(
            ProducerFactory<String, byte[]> deadLetterProducerFactory) {
        return new KafkaTemplate<>(deadLetterProducerFactory);
    }
}
