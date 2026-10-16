package com.mnova.etrm.ops.rules;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Party;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.ops.OpsContext;
import com.mnova.etrm.ops.RuleResult;
import com.mnova.etrm.ops.TradeOpsRule;
import com.mnova.etrm.valuation.ValuationResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-deal credit check: recomputes the netted exposure to the counterparty including
 * this deal (replacing any prior version of it) and blocks if the limit would be exceeded.
 * Warns above a utilization threshold so credit can act before a hard breach.
 */
public final class CreditLimitRule implements TradeOpsRule {

    private final double warnUtilization;

    public CreditLimitRule(double warnUtilization) {
        this.warnUtilization = warnUtilization;
    }

    @Override
    public String name() {
        return "CreditLimit";
    }

    @Override
    public RuleResult evaluate(Deal d, TranStatus target, OpsContext ctx) {
        Party cpty = ctx.session().party(d.externalBu()).orElse(null);
        if (cpty == null || cpty.isInternal()) {
            return RuleResult.pass(name());
        }
        if (ctx.market().isEmpty()) {
            return RuleResult.warn(name(), "no market data; credit not checked");
        }
        List<Deal> nettingSet = new ArrayList<>(ctx.session().query(x ->
                d.externalBu().equals(x.externalBu())
                        && x.status().isLive()
                        && x.dealTrackingNum() != d.dealTrackingNum()));
        nettingSet.add(d);
        // The candidate deal is still PENDING/NEW here, so sum the netting set directly rather than
        // filtering on live status.
        double net = nettingSet.stream()
                .map(x -> ctx.valuation().value(x, ctx.marketData()))
                .mapToDouble(ValuationResult::totalValue)
                .sum();
        double exposure = Math.max(0, net);
        if (exposure > cpty.creditLimit()) {
            return RuleResult.block(name(), String.format("exposure %,.0f would exceed limit %,.0f for %s",
                    exposure, cpty.creditLimit(), cpty.id()));
        }
        if (cpty.creditLimit() > 0 && exposure / cpty.creditLimit() >= warnUtilization) {
            return RuleResult.warn(name(), String.format("utilization %.0f%% for %s", 100 * exposure / cpty.creditLimit(), cpty.id()));
        }
        return RuleResult.pass(name());
    }
}
