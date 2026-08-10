package com.sentinel.telemetry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelemetryReadingsTest {

    @Test
    void shouldAcceptNominalReadings() {
        TelemetryReadings readings = new TelemetryReadings(60.0, 3.0, 5.0, 40.0, 1500.0);

        assertThat(readings.temperatureCelsius()).isEqualTo(60.0);
        assertThat(readings.rotationSpeedRpm()).isEqualTo(1500.0);
    }

    /**
     * The distinction this whole platform depends on: a reading far outside the normal operating
     * range is still a valid measurement. Rejecting it at construction would silently discard the
     * exact events the alerting exists to catch.
     */
    @ParameterizedTest(name = "{0} °C is valid telemetry, however alarming")
    @ValueSource(doubles = {140.0, 300.0, -40.0})
    void shouldAcceptExtremeButPhysicallyPossibleTemperatures(double temperature) {
        assertThatCode(() -> new TelemetryReadings(temperature, 3.0, 5.0, 40.0, 1500.0))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectTemperatureBelowAbsoluteZero() {
        assertThatThrownBy(() -> new TelemetryReadings(-273.16, 3.0, 5.0, 40.0, 1500.0))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("absolute zero");
    }

    @Test
    void shouldAcceptTemperatureExactlyAtAbsoluteZero() {
        assertThatCode(() -> new TelemetryReadings(-273.15, 3.0, 5.0, 40.0, 1500.0))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectNegativeRotationSpeed() {
        assertThatThrownBy(() -> new TelemetryReadings(60.0, 3.0, 5.0, 40.0, -1.0))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("rotationSpeedRpm");
    }

    @Test
    void shouldRejectNegativePowerConsumption() {
        assertThatThrownBy(() -> new TelemetryReadings(60.0, 3.0, 5.0, -0.1, 1500.0))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("powerConsumptionKilowatts");
    }

    @Test
    void shouldRejectNegativeVibration() {
        assertThatThrownBy(() -> new TelemetryReadings(60.0, -3.0, 5.0, 40.0, 1500.0))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("vibrationMillimetresPerSecond");
    }

    @Test
    void shouldRejectNegativePressure() {
        assertThatThrownBy(() -> new TelemetryReadings(60.0, 3.0, -0.5, 40.0, 1500.0))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("pressureBar");
    }

    @Test
    void shouldAcceptZeroForQuantitiesThatCanLegitimatelyBeZero() {
        assertThatCode(() -> new TelemetryReadings(60.0, 0.0, 0.0, 0.0, 0.0))
                .doesNotThrowAnyException();
    }

    /**
     * NaN survives every ordering comparison, so an unchecked NaN would slip past all threshold
     * rules and make a faulty sensor look healthy.
     */
    @ParameterizedTest(name = "non-finite value {0} is rejected")
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void shouldRejectNonFiniteValues(double value) {
        assertThatThrownBy(() -> new TelemetryReadings(value, 3.0, 5.0, 40.0, 1500.0))
                .isInstanceOf(InvalidTelemetryException.class)
                .hasMessageContaining("finite");
    }
}
