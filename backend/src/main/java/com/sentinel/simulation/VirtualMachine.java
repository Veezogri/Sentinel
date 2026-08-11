package com.sentinel.simulation;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

import com.sentinel.machine.domain.Machine;
import com.sentinel.simulation.anomaly.Anomaly;
import com.sentinel.simulation.anomaly.AnomalyPhase;
import com.sentinel.simulation.anomaly.AnomalyType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;

/**
 * One simulated machine, carrying its own readings, its own fault and its own randomness between
 * ticks.
 *
 * <h2>Why this one is mutable</h2>
 * Everything in the domain is immutable; this is not, deliberately. A simulator <em>is</em> a
 * state machine: the whole point is that tick N+1 continues from tick N. Rebuilding the object
 * every tick would allocate five boxed doubles per machine per tick to express "the same machine,
 * slightly later", and would guarantee nothing extra — the object never escapes its engine and is
 * never shared across threads. The immutable value it produces, {@link TelemetryEvent}, is what
 * leaves.
 *
 * <h2>How a reading evolves</h2>
 * Each signal follows a discrete mean-reverting process:
 *
 * <pre>
 *   target = nominal + anomalyOffset
 *   next   = current + reversionRate × (target − current) + gaussianNoise
 * </pre>
 *
 * This is an AR(1) process, which matters for one concrete reason: it is <em>stationary</em>. A
 * plain random walk — {@code next = current + noise} — has unbounded variance and will wander to
 * absurd values over a long run, which is exactly the failure a long-running simulator must not
 * have. The pull toward the target bounds the spread instead of relying on clamping to hide it.
 *
 * <p>Because an anomaly moves the target rather than the reading, the climb into a fault and the
 * recovery out of it both fall out of this single line of arithmetic.
 */
public final class VirtualMachine {

    private final Machine machine;
    private final MachineProfile profile;
    private final RandomGenerator random;
    private final Map<Signal, Double> readings = new EnumMap<>(Signal.class);

    private Anomaly anomaly;

    /**
     * @param random this machine's own generator, so that adding or removing a machine does not
     *               shift the trajectory of any other
     */
    public VirtualMachine(Machine machine, MachineProfile profile, RandomGenerator random) {
        this.machine = Objects.requireNonNull(machine, "machine must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");

        // Start each signal exactly at nominal: a run then begins from a known healthy state
        // rather than from a random point that might already be in alert range.
        for (Signal signal : Signal.values()) {
            readings.put(signal, profile.of(signal).nominal());
        }
    }

    public UUID id() {
        return machine.id();
    }

    public Machine machine() {
        return machine;
    }

    /**
     * Advances the machine to {@code at} and reports what it measured.
     *
     * <p>Returns empty when the machine is in communication loss. Its internal state still
     * advances: the equipment keeps running while the link is down, so when the link returns the
     * readings must reflect elapsed time rather than resume where they were cut off.
     */
    public Optional<TelemetryEvent> advanceTo(Instant at, Supplier<UUID> eventIds) {
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(eventIds, "eventIds must not be null");

        clearFinishedAnomaly(at);
        evolveReadings(at);

        if (isSilent()) {
            return Optional.empty();
        }
        return Optional.of(new TelemetryEvent(eventIds.get(), machine.id(), at, currentReadings()));
    }

    /**
     * Starts a fault, replacing any fault already in progress.
     *
     * <p>Replacing rather than queueing or rejecting keeps the model honest: a machine has one
     * dominant failure mode at a time, and a test that forces a fault should get that fault.
     */
    public void startAnomaly(AnomalyType type, Instant at, Duration duration, double intensity) {
        this.anomaly = new Anomaly(type, at, duration, intensity);
    }

    /** Starts a fault at the magnitude that is typical for its type on this machine. */
    public void startAnomaly(AnomalyType type, Instant at, Duration duration) {
        startAnomaly(type, at, duration, defaultIntensityFor(type));
    }

    /** Where the current fault stands, or {@link AnomalyPhase#FINISHED} when there is none. */
    public AnomalyPhase anomalyPhaseAt(Instant at) {
        return anomaly == null ? AnomalyPhase.FINISHED : anomaly.phaseAt(at);
    }

    /** Whether the machine is currently unreachable and therefore emitting nothing. */
    public boolean isSilent() {
        return anomaly != null && anomaly.type().suppressesTelemetry();
    }

    public boolean isHealthy() {
        return anomaly == null;
    }

    /** The last computed value of one signal, for assertions and diagnostics. */
    public double valueOf(Signal signal) {
        return readings.get(signal);
    }

    /**
     * Rolls for a spontaneous fault. Does nothing if a fault is already in progress, which is what
     * makes {@code probability} mean "chance that a healthy machine falls ill on this tick"
     * rather than a chance to restart a fault already under way.
     */
    void maybeStartRandomAnomaly(Instant at, double probability, Duration duration) {
        if (anomaly != null || probability <= 0) {
            return;
        }
        if (random.nextDouble() >= probability) {
            return;
        }
        AnomalyType[] types = AnomalyType.values();
        AnomalyType type = types[random.nextInt(types.length)];

        double intensity = defaultIntensityFor(type);
        // A pressure fault is as likely to be a leak as a blockage.
        if (type == AnomalyType.PRESSURE_FAULT && random.nextBoolean()) {
            intensity = -intensity;
        }
        startAnomaly(type, at, duration, intensity);
    }

    private void clearFinishedAnomaly(Instant at) {
        if (anomaly != null && anomaly.hasFinishedBy(at)) {
            anomaly = null;
        }
    }

    private void evolveReadings(Instant at) {
        for (Signal signal : Signal.values()) {
            SignalProfile signalProfile = profile.of(signal);
            double current = readings.get(signal);
            double target = signalProfile.nominal() + anomalyOffsetFor(signal, at);
            double noise = random.nextGaussian() * signalProfile.noiseStdDev();

            double next = current + signalProfile.reversionRate() * (target - current) + noise;
            readings.put(signal, signalProfile.clamp(next));
        }
    }

    private double anomalyOffsetFor(Signal signal, Instant at) {
        if (anomaly == null) {
            return 0.0;
        }
        return anomaly.affects(signal) ? anomaly.offsetAt(at) : 0.0;
    }

    private double defaultIntensityFor(AnomalyType type) {
        if (!type.intensityIsRelative()) {
            return type.defaultIntensity();
        }
        Signal signal = type.affectedSignal().orElseThrow();
        return profile.of(signal).nominal() * type.defaultIntensity();
    }

    private TelemetryReadings currentReadings() {
        return new TelemetryReadings(
                readings.get(Signal.TEMPERATURE),
                readings.get(Signal.VIBRATION),
                readings.get(Signal.PRESSURE),
                readings.get(Signal.POWER),
                readings.get(Signal.ROTATION_SPEED));
    }
}
