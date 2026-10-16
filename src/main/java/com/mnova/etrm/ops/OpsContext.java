package com.mnova.etrm.ops;

import com.mnova.etrm.adapter.EndurSession;
import com.mnova.etrm.market.MarketData;
import com.mnova.etrm.valuation.ValuationEngine;

import java.util.Optional;

/** Everything a rule may need: platform session, current market data and the valuation engine. */
public record OpsContext(EndurSession session, MarketData marketData, ValuationEngine valuation) {

    public Optional<MarketData> market() {
        return Optional.ofNullable(marketData);
    }
}
