package com.sentinel.rule.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sentinel.alert.domain.AlertSeverity;
import com.sentinel.alert.domain.AlertType;
import com.sentinel.rule.domain.RuleResult;
import com.sentinel.testsupport.DomainFixtures;

class AbnormalPressureRuleTest {

    private final AbnormalPressureRule rule = AbnormalPressureRule.withDefaults();

    private RuleResult evaluateAt(double pressure) {
        return rule.evaluate(DomainFixtures.contextWith(DomainFixtures.readingsWithPressure(pressure)));
    }

    @ParameterizedTest(name = "{0} bar is inside the acceptable band")
    @ValueSource(doubles = {1.0, 1.01, 5.0, 9.99, 10.0})
    void shouldNotTriggerInsideTheBandIncludingItsBounds(double pressure) {
        assertThat(evaluateAt(pressure)).isInstanceOf(RuleResult.NotTriggered.class);
    }

    @ParameterizedTest(name = "{0} bar is below the minimum")
    @ValueSource(doubles = {0.99, 0.5, 0.0})
    void shouldTriggerBelowMinimum(double pressure) {
        assertThat(evaluateAt(pressure)).isInstanceOfSatisfying(RuleResult.Triggered.class, triggered -> {
            assertThat(triggered.alertType()).isEqualTo(AlertType.ABNORMAL_PRESSURE);
            assertThat(triggered.severity()).isEqualTo(AlertSeverity.WARNING);
            assertThat(triggered.message()).contains("below");
        });
    }

    @ParameterizedTest(name = "{0} bar is above the maximum")
    @ValueSource(doubles = {10.01, 15.0, 200.0})
    void shouldTriggerAboveMaximum(double pressure) {
        assertThat(evaluateAt(pressure)).isInstanceOfSatisfying(RuleResult.Triggered.class, triggered -> {
            assertThat(triggered.alertType()).isEqualTo(AlertType.ABNORMAL_PRESSURE);
            assertThat(triggered.message()).contains("exceeded");
        });
    }

    /** Both directions are faults, but they are different faults, so the message must say which. */
    @Test
    void shouldDistinguishUnderpressureFromOverpressureInTheMessage() {
        RuleResult low = evaluateAt(0.2);
        RuleResult high = evaluateAt(12.0);

        assertThat(((RuleResult.Triggered) low).message())
                .isNotEqualTo(((RuleResult.Triggered) high).message());
    }

    @Test
    void shouldRejectInvertedBand() {
        assertThatThrownBy(() -> new AbnormalPressureRule(10.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
