package com.sentinel.simulation;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The complete behavioural description of one machine type: one {@link SignalProfile} per
 * {@link Signal}.
 *
 * <p>An {@link EnumMap} rather than five named fields, because every consumer wants to iterate
 * over the signals generically. Named fields would force each of them to spell out all five.
 */
public record MachineProfile(Map<Signal, SignalProfile> signals) {

    public MachineProfile {
        Objects.requireNonNull(signals, "signals must not be null");
        for (Signal signal : Signal.values()) {
            if (!signals.containsKey(signal)) {
                throw new IllegalArgumentException("missing profile for signal " + signal);
            }
        }
        signals = Map.copyOf(signals);
    }

    public SignalProfile of(Signal signal) {
        SignalProfile profile = signals.get(signal);
        if (profile == null) {
            throw new IllegalArgumentException("no profile for signal " + signal);
        }
        return profile;
    }
}
