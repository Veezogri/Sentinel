package com.sentinel.testsupport;

import org.testcontainers.DockerClientFactory;

/**
 * Whether a container runtime is reachable.
 *
 * <p>Integration tests disable themselves instead of failing when Docker is absent, so that
 * {@code mvn verify} stays green on a machine that cannot run containers. The trade-off is
 * explicit: a skipped test proves nothing, and the build output says "skipped" rather than
 * pretending otherwise. CI runs with Docker present, which is where these tests actually count.
 */
public final class DockerEnvironment {

    private DockerEnvironment() {
    }

    public static boolean isAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
