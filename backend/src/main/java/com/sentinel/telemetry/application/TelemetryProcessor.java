package com.sentinel.telemetry.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.machine.application.MachineStateStore;
import com.sentinel.machine.domain.HealthStatus;
import com.sentinel.machine.domain.Machine;
import com.sentinel.machine.domain.MachineState;
import com.sentinel.rule.domain.EvaluationContext;
import com.sentinel.rule.domain.RuleEngine;
import com.sentinel.rule.domain.RuleResult;
import com.sentinel.telemetry.domain.TelemetryEvent;

/**
 * Turns one telemetry event into an updated machine state and a set of rule findings.
 *
 * <p>The first place where the domain built in M1 is actually exercised at runtime. It is
 * transport-agnostic on purpose: it takes a {@link TelemetryEvent}, not a Kafka record, so it can
 * be unit-tested without a broker and reused unchanged if events ever arrive another way.
 *
 * <h2>Order of operations</h2>
 * Rules are evaluated <em>before</em> the state is updated, for two reasons. The resulting health
 * is part of the state being written, so it has to exist first. And rule evaluation is a pure
 * function of the event, so running it outside the store's per-machine lock keeps that lock as
 * short as possible.
 *
 * <h2>What it deliberately does not do</h2>
 * No persistence, no cache write, no {@code Alert} creation, no publishing. Findings are returned.
 * Turning them into alerts requires deduplication and cooldown state that arrives in M6, and
 * bolting it on here would make this class the thing it should not become.
 */
public class TelemetryProcessor {

    private static final Logger log = LoggerFactory.getLogger(TelemetryProcessor.class);

    private final MachineStateStore stateStore;
    private final MachineRegistry machineRegistry;
    private final RuleEngine ruleEngine;
    private final Clock clock;

    public TelemetryProcessor(
            MachineStateStore stateStore,
            MachineRegistry machineRegistry,
            RuleEngine ruleEngine,
            Clock clock) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.machineRegistry = Objects.requireNonNull(machineRegistry, "machineRegistry must not be null");
        this.ruleEngine = Objects.requireNonNull(ruleEngine, "ruleEngine must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public TelemetryProcessingResult process(TelemetryEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Instant processedAt = clock.instant();

        List<RuleResult.Triggered> findings = evaluateRules(event);
        HealthStatus health = RuleEngine.healthFrom(findings);

        MachineStateStore.StateUpdate update = stateStore.apply(event, health, processedAt);
        MachineState state = update.state();
        boolean stateAdvanced = update.advanced();

        if (!stateAdvanced) {
            // Not a duplicate: a genuinely different event that arrived after a newer one. It is
            // dropped for state purposes only; historical persistence (M4) will still want it.
            log.debug("event {} for machine {} is older than known state at {}, state unchanged",
                    event.eventId(), event.machineId(), state.lastTelemetryAt());
        }
        if (!findings.isEmpty()) {
            log.info("machine {} event {}: {} finding(s), health {}",
                    event.machineId(), event.eventId(), findings.size(), health);
        }

        return new TelemetryProcessingResult(state, findings, stateAdvanced);
    }

    private List<RuleResult.Triggered> evaluateRules(TelemetryEvent event) {
        Machine machine = machineRegistry.findById(event.machineId()).orElse(null);
        if (machine == null) {
            // Telemetry from a machine nobody registered. Not an error worth failing the record
            // over — the reading is still real and still updates state — but rules that depend on
            // machine metadata cannot run, so say so once rather than guessing a machine.
            log.warn("no registered machine {} for event {}, skipping rule evaluation",
                    event.machineId(), event.eventId());
            return List.of();
        }
        return ruleEngine.evaluate(new EvaluationContext(machine, event));
    }
}
