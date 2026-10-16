package com.mnova.etrm.valuation;

import com.mnova.etrm.domain.Deal;

import java.util.List;

/**
 * MtM result for one deal (Endur "Sim result" equivalent).
 * Unrealized MtM covers future periods; realized value covers delivered periods awaiting settlement.
 */
public record ValuationResult(Deal deal, List<PeriodValue> periods) {

    public ValuationResult {
        periods = List.copyOf(periods);
    }

    public double unrealizedMtm() {
        return periods.stream().filter(p -> !p.realized()).mapToDouble(PeriodValue::pv).sum();
    }

    public double realizedValue() {
        return periods.stream().filter(PeriodValue::realized).mapToDouble(PeriodValue::undiscounted).sum();
    }

    public double totalValue() {
        return unrealizedMtm() + realizedValue();
    }

    public double delta() {
        return periods.stream().mapToDouble(PeriodValue::delta).sum();
    }
}
