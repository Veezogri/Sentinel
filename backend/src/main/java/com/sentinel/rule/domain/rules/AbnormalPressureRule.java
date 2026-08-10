package com.sentinel.rule.domain.rules;

import java.util.Locale;

import com.sentinel.alert.domain.AlertSeverity;
import com.sentinel.alert.domain.AlertType;
import com.sentinel.rule.domain.EvaluationContext;
import com.sentinel.rule.domain.Rule;
import com.sentinel.rule.domain.RuleResult;

/**
 * Reports pressure leaving its acceptable band.
 *
 * <p>Two-sided, unlike the temperature and vibration rules: both a collapse and a build-up are
 * faults, and they are different faults. A loss of pressure suggests a leak, an excess suggests a
 * blockage, so the message says which side was crossed even though both map to the same alert
 * type.
 *
 * <p>The band is inclusive — a reading exactly at {@code minBar} or {@code maxBar} is still
 * acceptable — which is the opposite convention from the single-sided rules and is deliberate:
 * there the threshold is the start of the problem, here the bounds are the edge of the normal
 * range.
 */
public record AbnormalPressureRule(double minBar, double maxBar) implements Rule {

    public static final double DEFAULT_MIN_BAR = 1.0;
    public static final double DEFAULT_MAX_BAR = 10.0;

    public AbnormalPressureRule {
        if (minBar > maxBar) {
            throw new IllegalArgumentException("minBar (" + minBar + ") must not exceed maxBar (" + maxBar + ")");
        }
    }

    public static AbnormalPressureRule withDefaults() {
        return new AbnormalPressureRule(DEFAULT_MIN_BAR, DEFAULT_MAX_BAR);
    }

    @Override
    public String name() {
        return "abnormal-pressure";
    }

    @Override
    public RuleResult evaluate(EvaluationContext context) {
        double pressure = context.readings().pressureBar();

        if (pressure < minBar) {
            return RuleResult.triggered(AlertType.ABNORMAL_PRESSURE, AlertSeverity.WARNING,
                    String.format(Locale.ROOT,
                            "Pressure %.2f bar fell below the minimum of %.2f bar", pressure, minBar));
        }
        if (pressure > maxBar) {
            return RuleResult.triggered(AlertType.ABNORMAL_PRESSURE, AlertSeverity.WARNING,
                    String.format(Locale.ROOT,
                            "Pressure %.2f bar exceeded the maximum of %.2f bar", pressure, maxBar));
        }
        return RuleResult.notTriggered();
    }
}
