package com.sentinel.telemetry.domain;

/**
 * The set of physical measurements a machine reports in one sample.
 *
 * <p>Extracted from {@link TelemetryEvent} because the same five values are needed in two
 * different roles: as part of an event that happened, and as the last known values of a machine.
 * Repeating the fields in both places would guarantee they eventually drift apart.
 *
 * <p>Units are carried in the field names rather than in wrapper types. Five value objects
 * ({@code Temperature}, {@code Pressure}, …) would remove the same ambiguity, but at this stage
 * they would add five classes whose only behaviour is to hold a double. Naming closes the real
 * defect — a reader cannot tell whether {@code pressure} is bar or psi — at no structural cost.
 *
 * <h2>Validation policy</h2>
 * The compact constructor rejects only what cannot physically exist. It deliberately accepts
 * extreme-but-possible values: a 140 °C reading is valid telemetry describing a critical
 * situation, and it is the rule engine's job to react to it, not this constructor's job to
 * discard it.
 */
public record TelemetryReadings(
        double temperatureCelsius,
        double vibrationMillimetresPerSecond,
        double pressureBar,
        double powerConsumptionKilowatts,
        double rotationSpeedRpm) {

    /** Nothing colder than absolute zero can be measured; below this the sensor is broken. */
    private static final double ABSOLUTE_ZERO_CELSIUS = -273.15;

    public TelemetryReadings {
        requireFinite(temperatureCelsius, "temperatureCelsius");
        requireFinite(vibrationMillimetresPerSecond, "vibrationMillimetresPerSecond");
        requireFinite(pressureBar, "pressureBar");
        requireFinite(powerConsumptionKilowatts, "powerConsumptionKilowatts");
        requireFinite(rotationSpeedRpm, "rotationSpeedRpm");

        if (temperatureCelsius < ABSOLUTE_ZERO_CELSIUS) {
            throw new InvalidTelemetryException(
                    "temperatureCelsius must not be below absolute zero, got " + temperatureCelsius);
        }
        // Vibration is a magnitude, pressure is absolute, and neither consumed power nor
        // rotation speed is signed in this model: negatives mean a faulty or misread sensor.
        requireNotNegative(vibrationMillimetresPerSecond, "vibrationMillimetresPerSecond");
        requireNotNegative(pressureBar, "pressureBar");
        requireNotNegative(powerConsumptionKilowatts, "powerConsumptionKilowatts");
        requireNotNegative(rotationSpeedRpm, "rotationSpeedRpm");
    }

    private static void requireFinite(double value, String field) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new InvalidTelemetryException(field + " must be a finite number, got " + value);
        }
    }

    private static void requireNotNegative(double value, String field) {
        if (value < 0) {
            throw new InvalidTelemetryException(field + " must not be negative, got " + value);
        }
    }
}
