package com.sentinel.rule.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sentinel.alert.domain.AlertSeverity;
import com.sentinel.alert.domain.AlertType;
import com.sentinel.rule.domain.RuleResult;
import com.sentinel.testsupport.DomainFixtures;

class HighTemperatureRuleTest {

    private final HighTemperatureRule rule = HighTemperatureRule.withDefaults();

    private RuleResult evaluateAt(double temperature) {
        return rule.evaluate(DomainFixtures.contextWith(DomainFixtures.readingsWithTemperature(temperature)));
    }

    @ParameterizedTest(name = "{0} °C is normal")
    @ValueSource(doubles = {20.0, 60.0, 79.9})
    void shouldNotTriggerBelowWarningThreshold(double temperature) {
        assertThat(evaluateAt(temperature)).isInstanceOf(RuleResult.NotTriggered.class);
    }

    /**
     * The boundary convention, pinned deliberately: a threshold of 80 means "80 is already a
     * problem", not "anything above 80". Changing this changes alerting behaviour, so it should
     * break a test.
     */
    @ParameterizedTest(name = "{0} °C yields {1}")
    @CsvSource({
            "79.99, WARNING, false",
            "80.0,  WARNING, true",
            "80.01, WARNING, true",
            "94.99, WARNING, true",
            "95.0,  CRITICAL, true",
            "95.01, CRITICAL, true",
            "140.0, CRITICAL, true"
    })
    void shouldApplyInclusiveThresholds(double temperature, AlertSeverity expectedSeverity, boolean shouldTrigger) {
        RuleResult result = evaluateAt(temperature);

        if (!shouldTrigger) {
            assertThat(result).isInstanceOf(RuleResult.NotTriggered.class);
            return;
        }
        assertThat(result)
                .isInstanceOfSatisfying(RuleResult.Triggered.class, triggered -> {
                    assertThat(triggered.severity()).isEqualTo(expectedSeverity);
                    assertThat(triggered.alertType()).isEqualTo(AlertType.HIGH_TEMPERATURE);
                });
    }

    @Test
    void shouldDescribeTheMeasurementAndTheThresholdItCrossed() {
        RuleResult result = evaluateAt(96.5);

        assertThat(result).isInstanceOfSatisfying(RuleResult.Triggered.class, triggered ->
                assertThat(triggered.message())
                        .contains("96.5")
                        .contains("95.0")
                        .contains("critical"));
    }

    @Test
    void shouldRejectWarningThresholdAboveCriticalThreshold() {
        assertThatThrownBy(() -> new HighTemperatureRule(100.0, 90.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowThresholdsToBeConfiguredPerInstance() {
        HighTemperatureRule strict = new HighTemperatureRule(40.0, 50.0);

        RuleResult result = strict.evaluate(
                DomainFixtures.contextWith(DomainFixtures.readingsWithTemperature(45.0)));

        assertThat(result).isInstanceOfSatisfying(RuleResult.Triggered.class, triggered ->
                assertThat(triggered.severity()).isEqualTo(AlertSeverity.WARNING));
    }
}
