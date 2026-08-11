package com.sentinel.simulation;

/**
 * How one {@link Signal} behaves on one machine type.
 *
 * <p>These five numbers fully describe the evolution of a quantity, which is why they are grouped
 * rather than spread across parallel arrays or a fifteen-argument profile record.
 *
 * @param nominal        the value the signal settles around when nothing is wrong
 * @param noiseStdDev    standard deviation of the per-tick random perturbation. Small relative to
 *                       {@code nominal}: sensor jitter, not a new draw each tick
 * @param reversionRate  fraction of the gap to the target closed per tick, in {@code (0, 1]}. It
 *                       sets how fast the signal chases a moving target, so it governs both how
 *                       sharply an anomaly develops and how quickly the machine recovers
 * @param min            lowest value the sensor can physically report
 * @param max            highest value the sensor can physically report
 */
public record SignalProfile(
        double nominal,
        double noiseStdDev,
        double reversionRate,
        double min,
        double max) {

    public SignalProfile {
        if (reversionRate <= 0 || reversionRate > 1) {
            throw new IllegalArgumentException(
                    "reversionRate must be in (0, 1], got " + reversionRate);
        }
        if (noiseStdDev < 0) {
            throw new IllegalArgumentException("noiseStdDev must not be negative, got " + noiseStdDev);
        }
        if (min > max) {
            throw new IllegalArgumentException("min (" + min + ") must not exceed max (" + max + ")");
        }
        if (nominal < min || nominal > max) {
            throw new IllegalArgumentException(
                    "nominal (" + nominal + ") must lie within [" + min + ", " + max + "]");
        }
    }

    /** Confines a value to what the sensor could report. */
    public double clamp(double value) {
        return Math.min(max, Math.max(min, value));
    }
}
