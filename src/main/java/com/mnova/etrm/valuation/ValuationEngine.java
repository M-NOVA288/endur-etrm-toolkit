package com.mnova.etrm.valuation;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.DeliveryPeriod;
import com.mnova.etrm.market.MarketData;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Mark-to-market engine for fixed-price commodity deals.
 *
 * <p>All supported toolsets share the same economics per period:
 * {@code PV = sign x volume x (indexPrice - fixedPrice) x DF}. They differ in
 * discounting (futures are margined daily so DF = 1) and in what is delivered
 * (physical vs cash), which matters for settlement but not for MtM.</p>
 *
 * <p>Delivered periods are valued off realized fixings and reported as realized value;
 * future periods are valued off the forward curve and reported as unrealized MtM.</p>
 */
public final class ValuationEngine {

    private static final Logger LOG = Logger.getLogger(ValuationEngine.class.getName());

    public ValuationResult value(Deal deal, MarketData md) {
        List<PeriodValue> out = new ArrayList<>();
        int sign = deal.buySell().sign();
        for (DeliveryPeriod p : deal.periods()) {
            boolean realized = p.isRealized(md.valuationDate());
            double price = md.priceFor(deal.index(), p.month(), realized);
            double signedVol = sign * p.volume();
            double undiscounted = signedVol * (price - deal.fixedPrice());
            double df = (!deal.toolset().isDiscounted() || realized) ? 1.0 : md.discountFactor(p.paymentDate());
            double pv = undiscounted * df;
            double delta = realized ? 0.0 : signedVol * df;
            out.add(new PeriodValue(p.month(), signedVol, price, deal.fixedPrice(), p.paymentDate(),
                    df, undiscounted, pv, delta, realized));
        }
        return new ValuationResult(deal, out);
    }

    /**
     * Values a batch, isolating failures so one bad deal (e.g. missing curve) does not
     * abort the EOD run. Failures are collected for production-support follow-up.
     */
    public BatchResult valueAll(List<Deal> deals, MarketData md) {
        List<ValuationResult> ok = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Deal d : deals) {
            try {
                ok.add(value(d, md));
            } catch (RuntimeException e) {
                String msg = "Deal " + d.dealTrackingNum() + " failed valuation: " + e.getMessage();
                LOG.warning(msg);
                errors.add(msg);
            }
        }
        return new BatchResult(ok, errors);
    }

    public record BatchResult(List<ValuationResult> results, List<String> errors) {
        public double totalUnrealized() {
            return results.stream().mapToDouble(ValuationResult::unrealizedMtm).sum();
        }
    }
}
