package com.mnova.etrm.ops;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.TranStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Runs the configured pre-process rules for a deal status change.
 * A rule that throws is treated as BLOCK so that a defect never lets a bad trade through.
 */
public final class OpsServiceEngine {

    private static final Logger LOG = Logger.getLogger(OpsServiceEngine.class.getName());

    private final List<TradeOpsRule> rules;

    public OpsServiceEngine(List<TradeOpsRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public Outcome run(Deal deal, TranStatus target, OpsContext ctx) {
        List<RuleResult> results = new ArrayList<>();
        for (TradeOpsRule rule : rules) {
            if (!rule.appliesTo(target)) {
                continue;
            }
            RuleResult r;
            try {
                r = rule.evaluate(deal, target, ctx);
            } catch (RuntimeException e) {
                r = RuleResult.block(rule.name(), "rule error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (r.severity() != RuleResult.Severity.PASS) {
                RuleResult logged = r;
                LOG.fine(() -> "Deal " + deal.dealTrackingNum() + " -> " + target + " " + rule.name() + " "
                        + logged.severity() + ": " + logged.message());
            }
            results.add(r);
        }
        return new Outcome(results);
    }

    public record Outcome(List<RuleResult> results) {
        public boolean blocked() {
            return results.stream().anyMatch(RuleResult::isBlocking);
        }

        public List<RuleResult> issues() {
            return results.stream().filter(r -> r.severity() != RuleResult.Severity.PASS).toList();
        }

        public String summary() {
            return issues().stream().map(r -> r.severity() + " " + r.rule() + ": " + r.message())
                    .reduce((a, b) -> a + "; " + b).orElse("all rules passed");
        }
    }
}
