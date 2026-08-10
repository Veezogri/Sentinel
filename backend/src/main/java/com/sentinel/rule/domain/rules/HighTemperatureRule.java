package com.sentinel.rule.domain.rules;

import java.util.Locale;

import com.sentinel.alert.domain.AlertSeverity;
import com.sentinel.alert.domain.AlertType;
import com.sentinel.rule.domain.EvaluationContext;
import com.sentinel.rule.domain.Rule;
import com.sentinel.rule.domain.RuleResult;

/**
 * Reports overheating, at two levels of seriousness.
 *
 * <p>Thresholds are constructor arguments, not constants read from inside the method: a rule whose
 * limits are baked into its logic cannot be reused for a machine type with a different normal
 * range, and cannot be tested at its boundaries without editing it. The defaults are exposed as
 * named constants so no unexplained number appears in the code.
 *
 * <p>The comparison is inclusive: a reading exactly at the threshold triggers. "Critical at 95 °C"
 * reading as "critical above 95 °C" would be a surprising interpretation of a limit, and the
 * boundary case is pinned by tests.
 */
public record HighTemperatureRule(double warningCelsius, double criticalCelsius) implements Rule {

    public static final double DEFAULT_WARNING_CELSIUS = 80.0;
    public static final double DEFAULT_CRITICAL_CELSIUS = 95.0;

    public HighTemperatureRule {
        if (warningCelsius > criticalCelsius) {
            throw new IllegalArgumentException(
                    "warningCelsius (" + warningCelsius + ") must not exceed criticalCelsius (" + criticalCelsius + ")");
        }
    }

    public static HighTemperatureRule withDefaults() {
        return new HighTemperatureRule(DEFAULT_WARNING_CELSIUS, DEFAULT_CRITICAL_CELSIUS);
    }

    @Override
    public String name() {
        return "high-temperature";
    }

    @Override
    public RuleResult evaluate(EvaluationContext context) {
        double temperature = context.readings().temperatureCelsius();

        if (temperature >= criticalCelsius) {
            return RuleResult.triggered(AlertType.HIGH_TEMPERATURE, AlertSeverity.CRITICAL,
                    describe(temperature, criticalCelsius, "critical"));
        }
        if (temperature >= warningCelsius) {
            return RuleResult.triggered(AlertType.HIGH_TEMPERATURE, AlertSeverity.WARNING,
                    describe(temperature, warningCelsius, "warning"));
        }
        return RuleResult.notTriggered();
    }

    // Locale.ROOT: without it, the decimal separator follows the server's default locale and the
    // same alert reads "95.0" or "95,0" depending on where the process happens to run.
    private static String describe(double temperature, double threshold, String level) {
        return String.format(Locale.ROOT,
                "Temperature %.1f °C reached the %s threshold of %.1f °C", temperature, level, threshold);
    }
}
