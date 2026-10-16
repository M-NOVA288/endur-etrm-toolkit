package com.mnova.etrm.valuation;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.market.MarketData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Day-over-day P&amp;L attribution, the kind of report desk heads and product control
 * use to sign off EOD. Total change is split into:
 * <ul>
 *   <li><b>new deals</b> - value of deals booked since the prior run</li>
 *   <li><b>time</b> - revaluing old deals on yesterday's curves at today's date (roll-down, realization)</li>
 *   <li><b>market</b> - effect of today's curves vs yesterday's on existing deals</li>
 * </ul>
 */
public final class PnlExplain {

    private final ValuationEngine engine;

    public PnlExplain(ValuationEngine engine) {
        this.engine = engine;
    }

    public Explain explain(List<Deal> priorDeals, List<Deal> currentDeals, MarketData prior, MarketData current) {
        Map<Long, Deal> priorById = new HashMap<>();
        priorDeals.forEach(d -> priorById.put(d.dealTrackingNum(), d));

        MarketData priorCurvesToday = prior.copyTo(current.valuationDate());
        double newDeals = 0;
        double time = 0;
        double market = 0;
        for (Deal d : currentDeals) {
            double v1 = engine.value(d, current).totalValue();
            if (!priorById.containsKey(d.dealTrackingNum())) {
                newDeals += v1;
                continue;
            }
            double v0 = engine.value(priorById.get(d.dealTrackingNum()), prior).totalValue();
            double vt = engine.value(d, priorCurvesToday).totalValue();
            time += vt - v0;
            market += v1 - vt;
        }
        return new Explain(newDeals, time, market);
    }

    public record Explain(double newDeals, double time, double market) {
        public double total() {
            return newDeals + time + market;
        }
    }
}
