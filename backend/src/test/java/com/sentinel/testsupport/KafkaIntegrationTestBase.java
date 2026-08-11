package com.sentinel.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for tests that need a real broker.
 *
 * <p>Testcontainers rather than the Compose stack, so that {@code mvn verify} never depends on
 * someone having run {@code docker compose up} first, and so each run starts from an empty broker
 * instead of inheriting whatever the last run left in a topic.
 *
 * <p>The container is {@code static}, so all subclasses share one broker for the whole test JVM.
 * Starting a broker per test class would multiply a several-second startup by the number of
 * classes for no isolation benefit — topics are recreated by the application and the tests use
 * distinct machine identifiers.
 *
 * <p>The image matches the broker in {@code docker-compose.yml}: testing against a different
 * Kafka version than the one used locally would be a slow way to find version-specific bugs.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@RequiresDocker
public abstract class KafkaIntegrationTestBase {

    @Container
    @ServiceConnection
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.0"));

    /** For tests that need to talk to the broker directly rather than through the application. */
    protected static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
