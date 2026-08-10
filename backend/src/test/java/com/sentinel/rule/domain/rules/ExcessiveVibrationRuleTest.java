package com.sentinel.rule.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.sentinel.alert.domain.AlertSeverity;
import com.sentinel.alert.domain.AlertType;
import com.sentinel.rule.domain.RuleResult;
import com.sentinel.testsupport.DomainFixtures;

class ExcessiveVibrationRuleTest {

    private final ExcessiveVibrationRule rule = ExcessiveVibrationRule.withDefaults();

    private RuleResult evaluateAt(double vibration) {
        return rule.evaluate(DomainFixtures.contextWith(DomainFixtures.readingsWithVibration(vibration)));
    }

    @ParameterizedTest(name = "{0} mm/s yields {1}")
    @CsvSource({
            "0.0,   WARNING, false",
            "7.99,  WARNING, false",
            "8.0,   WARNING, true",
            "8.01,  WARNING, true",
            "13.99, WARNING, true",
            "14.0,  CRITICAL, true",
            "25.0,  CRITICAL, true"
    })
    void shouldApplyInclusiveThresholds(double vibration, AlertSeverity expectedSeverity, boolean shouldTrigger) {
        RuleResult result = evaluateAt(vibration);

        if (!shouldTrigger) {
            assertThat(result).isInstanceOf(RuleResult.NotTriggered.class);
            return;
        }
        assertThat(result).isInstanceOfSatisfying(RuleResult.Triggered.class, triggered -> {
            assertThat(triggered.severity()).isEqualTo(expectedSeverity);
            assertThat(triggered.alertType()).isEqualTo(AlertType.EXCESSIVE_VIBRATION);
        });
    }

    @Test
    void shouldNotTriggerOnAStationaryMachine() {
        assertThat(evaluateAt(0.0)).isInstanceOf(RuleResult.NotTriggered.class);
    }

    @Test
    void shouldRejectWarningThresholdAboveCriticalThreshold() {
        assertThatThrownBy(() -> new ExcessiveVibrationRule(20.0, 10.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
