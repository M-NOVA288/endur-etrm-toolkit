package com.mnova.etrm.settlement;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.DeliveryPeriod;
import com.mnova.etrm.domain.Toolset;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.market.MarketData;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates settlement cashflows for delivered periods.
 * <ul>
 *   <li>Physical (gas/power): invoiced at full value, buyer pays volume x fixed price.</li>
 *   <li>Swaps: net cash settlement, sign x volume x (fixing - fixed).</li>
 *   <li>Futures: settled through exchange variation margin, so no bilateral cashflow.</li>
 * </ul>
 */
public final class CashflowGenerator {

    public List<Cashflow> generate(List<Deal> deals, MarketData md) {
        List<Cashflow> out = new ArrayList<>();
        for (Deal d : deals) {
            if (d.status() == TranStatus.CANCELLED || d.status() == TranStatus.PENDING
                    || d.toolset() == Toolset.COM_FUT) {
                continue;
            }
            for (DeliveryPeriod p : d.periods()) {
                if (!p.isRealized(md.valuationDate())) {
                    continue;
                }
                out.add(toCashflow(d, p, md));
            }
        }
        return out;
    }

    private Cashflow toCashflow(Deal d, DeliveryPeriod p, MarketData md) {
        int sign = d.buySell().sign();
        if (d.toolset().isPhysical()) {
            double amount = -sign * p.volume() * d.fixedPrice();
            return new Cashflow(d.dealTrackingNum(), d.externalBu(), d.internalBu(), p.month(), p.paymentDate(),
                    d.currency(), p.volume(), d.fixedPrice(), amount, Cashflow.Type.COMMODITY);
        }
        double fixing = md.priceFor(d.index(), p.month(), true);
        double amount = sign * p.volume() * (fixing - d.fixedPrice());
        return new Cashflow(d.dealTrackingNum(), d.externalBu(), d.internalBu(), p.month(), p.paymentDate(),
                d.currency(), p.volume(), fixing, amount, Cashflow.Type.FINANCIAL);
    }
}
