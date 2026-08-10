package com.sentinel.rule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sentinel.alert.domain.AlertSeverity;
import com.sentinel.alert.domain.AlertType;
import com.sentinel.machine.domain.HealthStatus;
import com.sentinel.rule.domain.rules.AbnormalPressureRule;
import com.sentinel.rule.domain.rules.ExcessiveVibrationRule;
import com.sentinel.rule.domain.rules.HighTemperatureRule;
import com.sentinel.telemetry.domain.TelemetryReadings;
import com.sentinel.testsupport.DomainFixtures;

class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine(List.of(
            HighTemperatureRule.withDefaults(),
            ExcessiveVibrationRule.withDefaults(),
            AbnormalPressureRule.withDefaults()));

    private List<RuleResult.Triggered> evaluate(TelemetryReadings readings) {
        return engine.evaluate(DomainFixtures.contextWith(readings));
    }

    @Test
    void shouldReturnNoFindingsWhenEverythingIsNominal() {
        assertThat(evaluate(DomainFixtures.nominalReadings())).isEmpty();
    }

    @Test
    void shouldReturnASingleFindingWhenOneRuleTriggers() {
        List<RuleResult.Triggered> findings = evaluate(DomainFixtures.readingsWithTemperature(96.0));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.alertType()).isEqualTo(AlertType.HIGH_TEMPERATURE);
            assertThat(finding.severity()).isEqualTo(AlertSeverity.CRITICAL);
        });
    }

    /**
     * One event can be evidence of several distinct problems, and reporting only the first would
     * hide the others until the first is fixed.
     */
    @Test
    void shouldReturnEveryFindingWhenSeveralRulesTrigger() {
        TelemetryReadings failing = new TelemetryReadings(96.0, 20.0, 0.2, 40.0, 1500.0);

        List<RuleResult.Triggered> findings = evaluate(failing);

        assertThat(findings).hasSize(3)
                .extracting(RuleResult.Triggered::alertType)
                .containsExactly(
                        AlertType.HIGH_TEMPERATURE,
                        AlertType.EXCESSIVE_VIBRATION,
                        AlertType.ABNORMAL_PRESSURE);
    }

    @Test
    void shouldEvaluateNothingWhenConfiguredWithNoRules() {
        RuleEngine empty = new RuleEngine(List.of());

        assertThat(empty.evaluate(DomainFixtures.contextWith(DomainFixtures.readingsWithTemperature(200.0))))
                .isEmpty();
    }

    @Test
    void shouldNotBeAffectedByLaterChangesToTheRuleListItWasGiven() {
        List<Rule> mutable = new ArrayList<>(List.of(HighTemperatureRule.withDefaults()));
        RuleEngine engineUnderTest = new RuleEngine(mutable);

        mutable.clear();

        assertThat(engineUnderTest.rules()).hasSize(1);
    }

    @Test
    void shouldRejectAttemptsToModifyItsRuleList() {
        assertThatThrownBy(() -> engine.rules().add(HighTemperatureRule.withDefaults()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReportNormalHealthWhenNothingTriggered() {
        assertThat(RuleEngine.healthFrom(List.of())).isEqualTo(HealthStatus.NORMAL);
    }

    /** Health is the worst finding, not the last or the first. */
    @Test
    void shouldReportWorstSeverityAsHealth() {
        List<RuleResult.Triggered> findings = evaluate(new TelemetryReadings(96.0, 9.0, 5.0, 40.0, 1500.0));

        assertThat(findings).hasSize(2);
        assertThat(RuleEngine.healthFrom(findings)).isEqualTo(HealthStatus.CRITICAL);
    }

    @Test
    void shouldReportWarningHealthWhenOnlyWarningsTriggered() {
        List<RuleResult.Triggered> findings = evaluate(DomainFixtures.readingsWithVibration(9.0));

        assertThat(RuleEngine.healthFrom(findings)).isEqualTo(HealthStatus.WARNING);
    }
}
