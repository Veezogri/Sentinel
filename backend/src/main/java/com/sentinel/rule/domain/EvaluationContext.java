package com.sentinel.rule.domain;

import java.util.Objects;

import com.sentinel.machine.domain.Machine;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;

/**
 * Everything a rule is allowed to look at when deciding.
 *
 * <p>Rules take this rather than a bare {@link TelemetryEvent}, for two reasons. The machine is
 * already needed — its type is what will make "normal pressure" mean different things for a pump
 * and a turbine. And the rules that motivate this project are not all single-sample: "above 85 °C
 * for 30 seconds" and "a 20 °C rise in 10 seconds" need previous state. Those arrive in M17, but
 * they will arrive as extra components on this record, leaving every existing rule signature
 * untouched — whereas passing the event directly would force every rule to change the day the
 * first temporal rule is written.
 *
 * <p>It carries only what exists today. Adding {@code previousState} or a sliding window now,
 * before any rule reads them, would be speculation.
 */
public record EvaluationContext(Machine machine, TelemetryEvent event) {

    public EvaluationContext {
        Objects.requireNonNull(machine, "machine must not be null");
        Objects.requireNonNull(event, "event must not be null");
        if (!machine.id().equals(event.machineId())) {
            throw new IllegalArgumentException(
                    "event belongs to machine " + event.machineId() + ", not " + machine.id());
        }
    }

    /** Shortcut for the common case: most rules only look at the measurements. */
    public TelemetryReadings readings() {
        return event.readings();
    }
}
