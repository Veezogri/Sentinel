package com.sentinel.telemetry.application;

import java.util.List;
import java.util.Objects;

import com.sentinel.machine.domain.MachineState;
import com.sentinel.rule.domain.RuleResult;

/**
 * What processing one telemetry event produced.
 *
 * <p>Returned rather than acted upon, because the decision of what to do with findings — raise an
 * alert, deduplicate it, broadcast it — belongs to later milestones. Handing back a value keeps
 * the processor testable without stubbing collaborators it does not yet have.
 *
 * @param state       the machine state after the event, which may be the previous state if the
 *                    event was not newer
 * @param findings    conditions the rules detected in this event; empty when nothing triggered
 * @param stateAdvanced whether this event actually moved the state forward. False means the event
 *                    was older than what was already known — distinct from a duplicate, and worth
 *                    keeping separate because the two have different causes and different fixes
 */
public record TelemetryProcessingResult(
        MachineState state,
        List<RuleResult.Triggered> findings,
        boolean stateAdvanced) {

    public TelemetryProcessingResult {
        Objects.requireNonNull(state, "state must not be null");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings must not be null"));
    }

    public boolean hasFindings() {
        return !findings.isEmpty();
    }
}
