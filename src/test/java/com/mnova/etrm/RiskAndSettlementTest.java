package com.mnova.etrm;

import com.mnova.etrm.domain.BuySell;
import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Party;
import com.mnova.etrm.domain.Toolset;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.market.MarketData;
import com.mnova.etrm.risk.CreditExposure;
import com.mnova.etrm.risk.HistoricalVaR;
import com.mnova.etrm.risk.PositionReport;
import com.mnova.etrm.settlement.Cashflow;
import com.mnova.etrm.settlement.CashflowGenerator;
import com.mnova.etrm.settlement.InvoiceService;
import com.mnova.etrm.valuation.ValuationEngine;
import com.mnova.etrm.valuation.ValuationResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskAndSettlementTest {

    private final ValuationEngine engine = new ValuationEngine();

    private ValuationResult valued(Deal d, MarketData md) {
        return engine.value(d, md);
    }

    @Test
    void positionsNetLongAndShortAndSkipNonLiveDeals() {
        MarketData md = TestData.flatMarket(4.0, 0.0);
        Deal longD = TestData.gasSwap().status(TranStatus.VALIDATED).build();
        Deal shortD = TestData.gasSwap().buySell(BuySell.SELL).quantity(400).status(TranStatus.VALIDATED).build();
        Deal cancelled = TestData.gasSwap().quantity(99_999).status(TranStatus.CANCELLED).build();
        PositionReport p = PositionReport.build(List.of(valued(longD, md), valued(shortD, md), valued(cancelled, md)));
        assertEquals(600 * 61, p.netVolume("BOOK1", "HH"), 1e-9);
        assertEquals(2, p.rows().size());
        assertEquals(600 * 61, p.deltaByIndex().get("HH"), 1e-9);
        assertTrue(p.render().contains("BOOK1"));
    }

    @Test
    void historicalVarPicksTheRightScenario() {
        MarketData md = TestData.flatMarket(4.0, 0.0);
        // History: -0.10, 0.05, -0.30, 0.20, 0.00, -0.05, 0.10, -0.20, 0.15, 0.25 ; delta 10,000
        // Sorted P&L: -3000, -2000, -1000, ... ; 90% -> index floor(0.1*10)=1 -> loss 2000
        double var = new HistoricalVaR(0.90, 1).compute(Map.of("HH", 10_000.0), md);
        assertEquals(2_000, var, 1e-9);
        double var4d = new HistoricalVaR(0.90, 4).compute(Map.of("HH", 10_000.0), md);
        assertEquals(4_000, var4d, 1e-9);
        assertEquals(0.0, new HistoricalVaR(0.95, 1).compute(Map.of(), md), 1e-12);
        assertThrows(MarketData.MissingMarketDataException.class,
                () -> new HistoricalVaR(0.95, 1).compute(Map.of("NOHIST", 1.0), md));
    }

    @Test
    void creditExposureNetsPerCounterpartyAndFlagsBreaches() {
        MarketData md = TestData.flatMarket(4.0, 0.0);
        Deal win = TestData.gasSwap().status(TranStatus.VALIDATED).build();                       // +30,500
        Deal loss = TestData.gasSwap().buySell(BuySell.SELL).fixedPrice(3.80).status(TranStatus.VALIDATED).build(); // -12,200
        List<CreditExposure.Line> lines = CreditExposure.report(List.of(valued(win, md), valued(loss, md)),
                Map.of("CPTY_A", Party.external("CPTY_A", "A", 10_000)));
        assertEquals(1, lines.size());
        assertEquals(18_300, lines.get(0).exposure(), 1e-6);
        assertTrue(lines.get(0).breached());
    }

    @Test
    void cashflowsForPhysicalSwapAndFutures() {
        MarketData md = TestData.flatMarket(4.0, 0.0).addFixing("HH", YearMonth.of(2026, 9), 3.20);
        LocalDate s = LocalDate.of(2026, 9, 1);
        LocalDate e = LocalDate.of(2026, 9, 30);
        Deal phys = TestData.gasSwap().dealTrackingNum(1).toolset(Toolset.PHYS_GAS).startDate(s).endDate(e).status(TranStatus.VALIDATED).build();
        Deal swap = TestData.gasSwap().dealTrackingNum(2).buySell(BuySell.SELL).startDate(s).endDate(e).status(TranStatus.VALIDATED).build();
        Deal fut = TestData.gasSwap().dealTrackingNum(3).toolset(Toolset.COM_FUT).startDate(s).endDate(e).status(TranStatus.VALIDATED).build();
        List<Cashflow> cfs = new CashflowGenerator().generate(List.of(phys, swap, fut), md);
        assertEquals(2, cfs.size());
        assertEquals(-30_000 * 3.50, cfs.get(0).amount(), 1e-6);           // we pay for gas bought
        assertEquals(Cashflow.Type.COMMODITY, cfs.get(0).type());
        assertEquals(-30_000 * (3.20 - 3.50), cfs.get(1).amount(), 1e-6);  // short swap, fixing below fixed -> receive
        assertEquals(LocalDate.of(2026, 10, 20), cfs.get(1).paymentDate());
    }

    @Test
    void invoicesNetOnlyForNettingCounterparties() {
        LocalDate pay = LocalDate.of(2026, 10, 20);
        Cashflow ar = new Cashflow(1, "A", "DESK", YearMonth.of(2026, 9), pay, "USD", 1, 1, 100, Cashflow.Type.FINANCIAL);
        Cashflow ap = new Cashflow(2, "A", "DESK", YearMonth.of(2026, 9), pay, "USD", 1, 1, -40, Cashflow.Type.FINANCIAL);

        List<InvoiceService.Invoice> netted = new InvoiceService(Set.of("A")).build(List.of(ar, ap));
        assertEquals(1, netted.size());
        assertEquals(60, netted.get(0).netAmount(), 1e-9);
        assertEquals("RECEIVE", netted.get(0).direction());

        List<InvoiceService.Invoice> gross = new InvoiceService(Set.of()).build(List.of(ar, ap));
        assertEquals(2, gross.size());
        assertFalse(gross.stream().anyMatch(i -> i.netAmount() == 60));
    }
}
