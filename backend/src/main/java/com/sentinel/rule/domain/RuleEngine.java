package com.sentinel.rule.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sentinel.alert.domain.AlertSeverity;
import com.sentinel.machine.domain.HealthStatus;

/**
 * Evaluates every configured rule against one telemetry event and collects the findings.
 *
 * <h2>Name</h2>
 * Called a rule engine rather than an alert engine because that is all it does: it produces
 * findings, and creates no {@link com.sentinel.alert.domain.Alert}. Turning findings into alerts
 * means deciding whether the condition is already open, whether it is within a cooldown window,
 * and what identity to give it — stateful decisions that arrive in M6 and will sit on top of
 * this. Naming this class {@code AlertEngine} today would promise behaviour it does not have.
 *
 * <h2>Concurrency</h2>
 * Immutable, holding an immutable list of stateless rules, so a single instance is safe to share
 * across all consumer threads. This is a property to preserve: the moment a rule keeps per-machine
 * state — as a temporal rule would — that state must live outside the rule, or every rule needs
 * its own synchronisation.
 *
 * <h2>Cost</h2>
 * Evaluation is linear in the number of rules and each current rule is a constant-time comparison,
 * so the engine is O(rules) per event with no allocation beyond the findings list. At the target
 * of thousands of events per second this is not expected to be the bottleneck — a claim to
 * confirm by measurement in M15, not to assume.
 */
public final class RuleEngine {

    private final List<Rule> rules;

    public RuleEngine(List<Rule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        // Defensive copy: the caller must not be able to change the rule set of a live engine.
        this.rules = List.copyOf(rules);
    }

    /**
     * Runs every rule and returns the conditions that were detected, in rule declaration order.
     *
     * <p>Returns an empty list when nothing triggered — never null, so callers never branch on
     * emptiness before iterating.
     */
    public List<RuleResult.Triggered> evaluate(EvaluationContext context) {
        Objects.requireNonNull(context, "context must not be null");

        List<RuleResult.Triggered> findings = new ArrayList<>();
        for (Rule rule : rules) {
            switch (rule.evaluate(context)) {
                case RuleResult.Triggered triggered -> findings.add(triggered);
                case RuleResult.NotTriggered ignored -> {
                    // Nothing to report for this rule.
                }
            }
        }
        return List.copyOf(findings);
    }

    /**
     * The health a machine is in given a set of findings: the worst severity present.
     *
     * <p>Lives here because it is the direct counterpart of evaluation, and putting it anywhere
     * else would mean two places know how findings map to health.
     */
    public static HealthStatus healthFrom(List<RuleResult.Triggered> findings) {
        Objects.requireNonNull(findings, "findings must not be null");
        return findings.stream()
                .map(RuleResult.Triggered::severity)
                .reduce(AlertSeverity::max)
                .map(AlertSeverity::impliedHealthStatus)
                .orElse(HealthStatus.NORMAL);
    }

    /** The rules this engine evaluates, in order. */
    public List<Rule> rules() {
        return rules;
    }
}
