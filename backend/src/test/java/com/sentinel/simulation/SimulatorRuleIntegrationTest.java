package com.sentinel.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.sentinel.alert.domain.AlertSeverity;
import com.sentinel.alert.domain.AlertType;
import com.sentinel.machine.domain.HealthStatus;
import com.sentinel.machine.domain.Machine;
import com.sentinel.rule.domain.EvaluationContext;
import com.sentinel.rule.domain.RuleEngine;
import com.sentinel.rule.domain.RuleResult;
import com.sentinel.rule.domain.rules.AbnormalPressureRule;
import com.sentinel.rule.domain.rules.ExcessiveVibrationRule;
import com.sentinel.rule.domain.rules.HighTemperatureRule;
import com.sentinel.simulation.anomaly.AnomalyType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.testsupport.SequentialUuids;

/**
 * Proves that the M1 domain and the M2 simulator actually fit together.
 *
 * <p>The wiring lives here, in the test, and nowhere in production code: the simulator has no
 * reference to {@link RuleEngine} and the engine has no reference to the simulator. Coupling them
 * would make the simulator a component of the alerting path rather than a source of events.
 */
class SimulatorRuleIntegrationTest {

    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");

    private final RuleEngine rules = new RuleEngine(List.of(
            HighTemperatureRule.withDefaults(),
            ExcessiveVibrationRule.withDefaults(),
            AbnormalPressureRule.withDefaults()));

    private List<RuleResult.Triggered> evaluateAll(SimulationEngine engine, List<TelemetryEvent> events) {
        Map<UUID, Machine> byId = engine.machines().stream()
                .collect(Collectors.toMap(Machine::id, Function.identity()));

        List<RuleResult.Triggered> findings = new ArrayList<>();
        for (TelemetryEvent event : events) {
            findings.addAll(rules.evaluate(new EvaluationContext(byId.get(event.machineId()), event)));
        }
        return findings;
    }

    @Test
    void shouldProduceNoFindingsForAHealthyFleet() {
        SimulationEngine engine =
                new SimulationEngine(SimulationConfig.of(10, 5L, START), new SequentialUuids());

        assertThat(evaluateAll(engine, engine.run(300))).isEmpty();
    }

    @Test
    void shouldTriggerACriticalHighTemperatureFindingWhenAMachineOverheats() {
        SimulationEngine engine =
                new SimulationEngine(SimulationConfig.of(1, 5L, START), new SequentialUuids());
        UUID machineId = engine.machines().get(0).id();

        engine.triggerAnomaly(machineId, AnomalyType.OVERHEATING, Duration.ofMinutes(2));
        List<RuleResult.Triggered> findings = evaluateAll(engine, engine.run(120));

        assertThat(findings).isNotEmpty();
        assertThat(findings).extracting(RuleResult.Triggered::alertType)
                .containsOnly(AlertType.HIGH_TEMPERATURE);
        assertThat(findings).extracting(RuleResult.Triggered::severity)
                .contains(AlertSeverity.WARNING, AlertSeverity.CRITICAL);
    }

    /**
     * A sustained fault produces a long run of findings from one incident. That is the raw
     * material deduplication will have to collapse into a single alert (M6).
     */
    @Test
    void shouldProduceManyFindingsFromASingleSustainedFault() {
        SimulationEngine engine =
                new SimulationEngine(SimulationConfig.of(1, 5L, START), new SequentialUuids());
        UUID machineId = engine.machines().get(0).id();

        engine.triggerAnomaly(machineId, AnomalyType.OVERHEATING, Duration.ofMinutes(2));

        assertThat(evaluateAll(engine, engine.run(120))).hasSizeGreaterThan(60);
    }

    @Test
    void shouldReportCriticalHealthWhileTheFaultIsAtFullStrength() {
        SimulationEngine engine =
                new SimulationEngine(SimulationConfig.of(1, 5L, START), new SequentialUuids());
        Machine machine = engine.machines().get(0);

        engine.triggerAnomaly(machine.id(), AnomalyType.OVERHEATING, Duration.ofMinutes(2));
        engine.run(60);

        TelemetryEvent atPeak = engine.tick().get(0);
        List<RuleResult.Triggered> findings =
                rules.evaluate(new EvaluationContext(machine, atPeak));

        assertThat(RuleEngine.healthFrom(findings)).isEqualTo(HealthStatus.CRITICAL);
    }

    @Test
    void shouldTriggerVibrationFindingsForAVibrationFault() {
        SimulationEngine engine =
                new SimulationEngine(SimulationConfig.of(1, 5L, START), new SequentialUuids());
        UUID machineId = engine.machines().get(0).id();

        engine.triggerAnomaly(machineId, AnomalyType.EXCESSIVE_VIBRATION, Duration.ofMinutes(2));
        List<RuleResult.Triggered> findings = evaluateAll(engine, engine.run(120));

        assertThat(findings).extracting(RuleResult.Triggered::alertType)
                .containsOnly(AlertType.EXCESSIVE_VIBRATION);
    }
}
