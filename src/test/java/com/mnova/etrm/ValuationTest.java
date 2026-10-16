package com.mnova.etrm;

import com.mnova.etrm.domain.BuySell;
import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Toolset;
import com.mnova.etrm.market.MarketData;
import com.mnova.etrm.valuation.PnlExplain;
import com.mnova.etrm.valuation.ValuationEngine;
import com.mnova.etrm.valuation.ValuationResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValuationTest {

    private final ValuationEngine engine = new ValuationEngine();

    @Test
    void undiscountedSwapMtmMatchesHandCalculation() {
        // Long 1000/d Nov+Dec at 3.50 vs flat 4.00: (30+31) * 1000 * 0.50
        ValuationResult r = engine.value(TestData.gasSwap().build(), TestData.flatMarket(4.0, 0.0));
        assertEquals(30_500, r.unrealizedMtm(), 1e-6);
        assertEquals(61_000, r.delta(), 1e-6);
        assertEquals(0, r.realizedValue(), 1e-12);
    }

    @Test
    void shortPositionFlipsSign() {
        ValuationResult r = engine.value(TestData.gasSwap().buySell(BuySell.SELL).build(), TestData.flatMarket(4.0, 0.0));
        assertEquals(-30_500, r.unrealizedMtm(), 1e-6);
    }

    @Test
    void swapIsDiscountedToPaymentDate() {
        MarketData md = TestData.flatMarket(4.0, 0.05);
        ValuationResult r = engine.value(TestData.gasSwap().build(), md);
        double dfNov = Math.exp(-0.05 * ChronoUnit.DAYS.between(TestData.VAL_DATE, LocalDate.of(2026, 12, 20)) / 365.0);
        double dfDec = Math.exp(-0.05 * ChronoUnit.DAYS.between(TestData.VAL_DATE, LocalDate.of(2027, 1, 20)) / 365.0);
        assertEquals(15_000 * dfNov + 15_500 * dfDec, r.unrealizedMtm(), 1e-6);
    }

    @Test
    void futuresAreNotDiscounted() {
        Deal fut = TestData.gasSwap().toolset(Toolset.COM_FUT).quantity(10_000)
                .startDate(LocalDate.of(2027, 6, 1)).endDate(LocalDate.of(2027, 6, 30)).build();
        ValuationResult r = engine.value(fut, TestData.flatMarket(4.0, 0.10));
        assertEquals(5_000, r.unrealizedMtm(), 1e-9);
    }

    @Test
    void deliveredPeriodsUseFixingsAndAreRealized() {
        MarketData md = TestData.flatMarket(4.0, 0.0).addFixing("HH", YearMonth.of(2026, 9), 3.00);
        Deal d = TestData.gasSwap().startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 11, 30)).build();
        ValuationResult r = engine.value(d, md);
        assertEquals(30 * 1000 * (3.00 - 3.50), r.realizedValue(), 1e-6);
        // Oct (31 days) and Nov (30 days) still open
        assertEquals(61 * 1000 * 0.50, r.unrealizedMtm(), 1e-6);
    }

    @Test
    void batchIsolatesFailures() {
        Deal good = TestData.gasSwap().build();
        Deal bad = TestData.gasSwap().index("MISSING").build();
        ValuationEngine.BatchResult b = engine.valueAll(List.of(good, bad), TestData.flatMarket(4.0, 0.0));
        assertEquals(1, b.results().size());
        assertEquals(1, b.errors().size());
        assertTrue(b.errors().get(0).contains("MISSING"));
    }

    @Test
    void pnlExplainComponentsSumToTotalChange() {
        MarketData prior = TestData.flatMarket(3.80, 0.0).copyTo(TestData.VAL_DATE.minusDays(1));
        MarketData today = TestData.flatMarket(4.10, 0.0);
        Deal existing = TestData.gasSwap().dealTrackingNum(1).build();
        Deal newDeal = TestData.gasSwap().dealTrackingNum(2).buySell(BuySell.SELL).fixedPrice(4.00).build();

        PnlExplain.Explain ex = new PnlExplain(engine).explain(List.of(existing), List.of(existing, newDeal), prior, today);

        double v0 = engine.value(existing, prior).totalValue();
        double v1 = engine.value(existing, today).totalValue() + engine.value(newDeal, today).totalValue();
        assertEquals(v1 - v0, ex.total(), 1e-6);
        assertEquals(61_000 * 0.30, ex.market(), 1e-6);
        assertEquals(-61_000 * 0.10, ex.newDeals(), 1e-6);
    }
}
