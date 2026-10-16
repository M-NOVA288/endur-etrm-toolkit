package com.mnova.etrm;

import com.mnova.etrm.adapter.EndurSession;
import com.mnova.etrm.adapter.InMemoryEndurSession;
import com.mnova.etrm.domain.BuySell;
import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Party;
import com.mnova.etrm.domain.Toolset;
import com.mnova.etrm.market.ForwardCurve;
import com.mnova.etrm.market.MarketData;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Shared fixtures. Valuation date 2026-10-15; flat HH curve at 4.00 with zero rates unless stated. */
public final class TestData {

    public static final LocalDate VAL_DATE = LocalDate.of(2026, 10, 15);

    private TestData() {
    }

    public static Deal.Builder gasSwap() {
        return Deal.builder()
                .toolset(Toolset.COM_SWAP).insType("GAS-SWAP-FF").buySell(BuySell.BUY)
                .internalBu("DESK").externalBu("CPTY_A").portfolio("BOOK1").trader("trader1")
                .index("HH").fixedPrice(3.50).quantity(1000).unit("MMBtu")
                .tradeDate(LocalDate.of(2026, 10, 1))
                .startDate(LocalDate.of(2026, 11, 1)).endDate(LocalDate.of(2026, 12, 31));
    }

    public static MarketData flatMarket(double price, double rate) {
        ForwardCurve hh = new ForwardCurve("HH", "USD/MMBtu");
        for (int m = 1; m <= 24; m++) {
            hh.put(YearMonth.of(2026, 1).plusMonths(m - 1), price);
        }
        MarketData md = new MarketData(VAL_DATE, rate).addCurve(hh);
        md.addHistory("HH", List.of(-0.10, 0.05, -0.30, 0.20, 0.00, -0.05, 0.10, -0.20, 0.15, 0.25));
        return md;
    }

    public static EndurSession session() {
        EndurSession s = new InMemoryEndurSession();
        s.registerParty(Party.internal("DESK", "Internal Desk"));
        s.registerParty(Party.external("CPTY_A", "Counterparty A", 1_000_000));
        s.registerParty(Party.external("CPTY_SUSP", "Suspended Cpty", 1_000_000).suspend());
        s.authorizeTrader("trader1", "BOOK1");
        return s;
    }
}
