package com.mnova.etrm.risk;

import com.mnova.etrm.domain.Party;
import com.mnova.etrm.valuation.ValuationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Current credit exposure per counterparty, netted across all live deals
 * (one netting set per external BU, as under an ISDA/NAESB master agreement).
 * Exposure = max(0, net MtM + realized receivables).
 */
public final class CreditExposure {

    public record Line(String counterparty, double netValue, double exposure, double limit) {
        public double utilization() {
            return limit <= 0 ? (exposure > 0 ? Double.POSITIVE_INFINITY : 0) : exposure / limit;
        }

        public boolean breached() {
            return exposure > limit;
        }
    }

    public static Map<String, Double> netValueByCounterparty(List<ValuationResult> results) {
        Map<String, Double> net = new TreeMap<>();
        for (ValuationResult r : results) {
            if (r.deal().status().isLive() || r.realizedValue() != 0) {
                net.merge(r.deal().externalBu(), r.totalValue(), Double::sum);
            }
        }
        return net;
    }

    public static List<Line> report(List<ValuationResult> results, Map<String, Party> parties) {
        List<Line> out = new ArrayList<>();
        netValueByCounterparty(results).forEach((cpty, net) -> {
            Party p = parties.get(cpty);
            double limit = p == null ? 0 : p.creditLimit();
            out.add(new Line(cpty, net, Math.max(0, net), limit));
        });
        return out;
    }
}
