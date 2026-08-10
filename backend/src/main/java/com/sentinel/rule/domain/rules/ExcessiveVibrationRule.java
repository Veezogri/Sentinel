package com.sentinel.rule.domain.rules;

import java.util.Locale;

import com.sentinel.alert.domain.AlertSeverity;
import com.sentinel.alert.domain.AlertType;
import com.sentinel.rule.domain.EvaluationContext;
import com.sentinel.rule.domain.Rule;
import com.sentinel.rule.domain.RuleResult;

/**
 * Reports excessive vibration, the usual early symptom of a bearing or alignment fault.
 *
 * <p>Same two-level, inclusive-threshold shape as {@link HighTemperatureRule}. Defaults are in
 * mm/s, the unit the readings carry.
 */
public record ExcessiveVibrationRule(double warningMillimetresPerSecond, double criticalMillimetresPerSecond)
        implements Rule {

    public static final double DEFAULT_WARNING_MM_PER_SECOND = 8.0;
    public static final double DEFAULT_CRITICAL_MM_PER_SECOND = 14.0;

    public ExcessiveVibrationRule {
        if (warningMillimetresPerSecond > criticalMillimetresPerSecond) {
            throw new IllegalArgumentException("warning threshold (" + warningMillimetresPerSecond
                    + ") must not exceed critical threshold (" + criticalMillimetresPerSecond + ")");
        }
    }

    public static ExcessiveVibrationRule withDefaults() {
        return new ExcessiveVibrationRule(DEFAULT_WARNING_MM_PER_SECOND, DEFAULT_CRITICAL_MM_PER_SECOND);
    }

    @Override
    public String name() {
        return "excessive-vibration";
    }

    @Override
    public RuleResult evaluate(EvaluationContext context) {
        double vibration = context.readings().vibrationMillimetresPerSecond();

        if (vibration >= criticalMillimetresPerSecond) {
            return RuleResult.triggered(AlertType.EXCESSIVE_VIBRATION, AlertSeverity.CRITICAL,
                    describe(vibration, criticalMillimetresPerSecond, "critical"));
        }
        if (vibration >= warningMillimetresPerSecond) {
            return RuleResult.triggered(AlertType.EXCESSIVE_VIBRATION, AlertSeverity.WARNING,
                    describe(vibration, warningMillimetresPerSecond, "warning"));
        }
        return RuleResult.notTriggered();
    }

    private static String describe(double vibration, double threshold, String level) {
        return String.format(Locale.ROOT,
                "Vibration %.2f mm/s reached the %s threshold of %.2f mm/s", vibration, level, threshold);
    }
}
