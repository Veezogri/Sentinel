package com.sentinel.rule.infrastructure;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sentinel.rule.domain.Rule;
import com.sentinel.rule.domain.RuleEngine;
import com.sentinel.rule.domain.rules.AbnormalPressureRule;
import com.sentinel.rule.domain.rules.ExcessiveVibrationRule;
import com.sentinel.rule.domain.rules.HighTemperatureRule;

/**
 * Assembles the active rule set.
 *
 * <p>The wiring is a Spring concern and lives here, at the edge; the rules themselves carry no
 * annotations and are constructed with plain {@code new}. That is what keeps them unit-testable
 * without a context, and what would let the same rule set be assembled from a database row in a
 * later milestone without touching the rules.
 *
 * <p>Thresholds come from each rule's documented defaults for now. Making them configurable is a
 * later concern, and doing it before the storage for that configuration exists would just move
 * the constants into a different file.
 */
@Configuration
public class RuleEngineConfiguration {

    @Bean
    List<Rule> activeRules() {
        return List.of(
                HighTemperatureRule.withDefaults(),
                ExcessiveVibrationRule.withDefaults(),
                AbnormalPressureRule.withDefaults());
    }

    /** Immutable and stateless, so one instance serves every consumer thread. */
    @Bean
    RuleEngine ruleEngine(List<Rule> activeRules) {
        return new RuleEngine(activeRules);
    }
}
