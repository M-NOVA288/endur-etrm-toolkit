package com.mnova.etrm.ops.rules;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.DeliveryPeriod;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.market.MarketData;
import com.mnova.etrm.ops.OpsContext;
import com.mnova.etrm.ops.RuleResult;
import com.mnova.etrm.ops.TradeOpsRule;

/**
 * Fat-finger check: blocks deals whose fixed price deviates from the volume-weighted
 * forward price by more than a tolerance. Warns (does not block) when no curve is available,
 * so a market data outage does not stop trading.
 */
public final class PriceToleranceRule implements TradeOpsRule {

    private final double tolerance;

    public PriceToleranceRule(double tolerance) {
        this.tolerance = tolerance;
    }

    @Override
    public String name() {
        return "PriceTolerance";
    }

    @Override
    public RuleResult evaluate(Deal d, TranStatus target, OpsContext ctx) {
        if (ctx.market().isEmpty() || !ctx.marketData().hasCurve(d.index())) {
            return RuleResult.warn(name(), "no curve for " + d.index() + "; price not checked");
        }
        MarketData md = ctx.marketData();
        double wSum = 0;
        double vSum = 0;
        for (DeliveryPeriod p : d.periods()) {
            if (p.isRealized(md.valuationDate())) {
                continue; // delivered months are priced off fixings, not the forward curve
            }
            wSum += md.curve(d.index()).price(p.month()) * p.volume();
            vSum += p.volume();
        }
        if (vSum == 0) {
            return RuleResult.warn(name(), "deal fully delivered (late booking); price not checked against curve");
        }
        double ref = wSum / vSum;
        double dev = Math.abs(d.fixedPrice() - ref) / Math.abs(ref);
        if (dev > tolerance) {
            return RuleResult.block(name(), String.format("price %.4f is %.1f%% away from market %.4f (tolerance %.1f%%)",
                    d.fixedPrice(), dev * 100, ref, tolerance * 100));
        }
        return RuleResult.pass(name());
    }
}
