package com.sentinel.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the boundary that the whole layering rests on.
 *
 * <p>Every milestone so far has claimed the domain is free of framework and serialisation
 * concerns. Now that Jackson and Spring Kafka are on the classpath, that claim is one convenient
 * annotation away from being false — and it would still compile. This test makes the claim
 * enforceable instead of aspirational.
 *
 * <p>It reads the sources rather than the bytecode because the thing being forbidden is the
 * dependency itself, and an import is the clearest evidence of one.
 */
class DomainPurityTest {

    private static final List<String> FORBIDDEN_IN_DOMAIN = List.of(
            "org.springframework",
            "com.fasterxml.jackson",
            "jakarta.persistence",
            "org.apache.kafka",
            "org.hibernate");

    @ParameterizedTest(name = "{0} must not depend on any framework")
    @ValueSource(strings = {
            "src/main/java/com/sentinel/machine/domain",
            "src/main/java/com/sentinel/telemetry/domain",
            "src/main/java/com/sentinel/alert/domain",
            "src/main/java/com/sentinel/rule/domain"})
    void shouldKeepDomainPackagesFrameworkFree(String packagePath) throws IOException {
        assertThat(forbiddenImportsUnder(Path.of(packagePath), FORBIDDEN_IN_DOMAIN)).isEmpty();
    }

    /**
     * The simulation core must stay runnable from a plain unit test. Its Spring wiring lives in
     * the separate {@code simulation.runtime} package, which is excluded here.
     */
    @Test
    void shouldKeepSimulationCoreFrameworkFree() throws IOException {
        List<String> violations = forbiddenImportsUnder(
                Path.of("src/main/java/com/sentinel/simulation"), FORBIDDEN_IN_DOMAIN).stream()
                .filter(violation -> !violation.contains("/runtime/"))
                .toList();

        assertThat(violations).isEmpty();
    }

    /** The domain must not reach into infrastructure either — dependencies point inward only. */
    @Test
    void shouldKeepDomainFreeOfInfrastructureImports() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String pkg : List.of("machine", "telemetry", "alert", "rule")) {
            violations.addAll(forbiddenImportsUnder(
                    Path.of("src/main/java/com/sentinel/" + pkg + "/domain"),
                    List.of("com.sentinel." + pkg + ".infrastructure", "com.sentinel.infrastructure")));
        }
        assertThat(violations).isEmpty();
    }

    private static List<String> forbiddenImportsUnder(Path root, List<String> forbidden) throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.strip();
                    if (!trimmed.startsWith("import ")) {
                        continue;
                    }
                    forbidden.stream()
                            .filter(trimmed::contains)
                            .forEach(bad -> violations.add(file + " -> " + trimmed));
                }
            }
        }
        return violations;
    }
}
