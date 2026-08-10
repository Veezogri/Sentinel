package com.sentinel.rule.domain;

/**
 * A single detection rule.
 *
 * <p>An interface here is not speculative: three implementations exist already, and the engine's
 * whole job is to hold a collection of them without knowing which. Implementations must be
 * stateless and immutable, so one instance can be evaluated concurrently by every Kafka consumer
 * thread without synchronisation.
 */
public interface Rule {

    /** A stable identifier used in logs and metrics, so a noisy rule can be named. */
    String name();

    /** Decides whether the watched condition is present. Must not mutate the context. */
    RuleResult evaluate(EvaluationContext context);
}
