package com.mnova.etrm;

import com.mnova.etrm.adapter.EndurSession;
import com.mnova.etrm.domain.BuySell;
import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Toolset;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.interfaces.ConfirmationWriter;
import com.mnova.etrm.interfaces.GlPostingExtract;
import com.mnova.etrm.interfaces.PositionReconciliation;
import com.mnova.etrm.interfaces.TradeEventDispatcher;
import com.mnova.etrm.interfaces.TradeFeedParser;
import com.mnova.etrm.lifecycle.TradeLifecycleService;
import com.mnova.etrm.market.MarketData;
import com.mnova.etrm.ops.OpsContext;
import com.mnova.etrm.ops.StandardRules;
import com.mnova.etrm.settlement.Cashflow;
import com.mnova.etrm.valuation.ValuationEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterfacesTest {

    private static final String HEADER =
            "trader,ext_ref,trade_date,toolset,ins_type,buy_sell,internal_bu,external_bu,portfolio,index,price,quantity,unit,start_date,end_date";

    @Test
    void parsesFeedByHeaderNameWithQuotedFieldsAndRejectsBadRows() throws IOException {
        String csv = HEADER + "\n"
                + "trader1,E1,2026-10-01,COM_SWAP,GAS-SWAP,B,DESK,CPTY_A,BOOK1,HH,3.5,\"10,000\",MMBtu,2026-11-01,2026-11-30\n"
                + "trader1,E2,2026-10-01,OPTION,GAS-OPT,B,DESK,CPTY_A,BOOK1,HH,3.5,1000,MMBtu,2026-11-01,2026-11-30\n"
                + "trader1,E3,2026-10-01,COM_SWAP,GAS-SWAP,B,DESK,CPTY_A,BOOK1,HH,abc,1000,MMBtu,2026-11-01,2026-11-30\n";
        TradeFeedParser.ParseResult r = new TradeFeedParser().parse(new StringReader(csv));
        assertEquals(1, r.drafts().size());
        Deal d = r.drafts().get(0);
        assertEquals(10_000, d.quantity(), 1e-9);
        assertEquals(BuySell.BUY, d.buySell());
        assertEquals(Toolset.COM_SWAP, d.toolset());
        assertEquals("E1", d.externalRef());
        assertEquals(2, r.rejections().size());
        assertTrue(r.rejections().get(0).reason().contains("unsupported toolset"));
        assertTrue(r.rejections().get(1).reason().contains("invalid number for price"));
        assertEquals(4, r.rejections().get(1).line());
    }

    @Test
    void feedWithMissingColumnFailsFast() {
        IOException e = assertThrows(IOException.class,
                () -> new TradeFeedParser().parse(new StringReader("ext_ref,trade_date\nX,2026-01-01\n")));
        assertTrue(e.getMessage().contains("missing column"));
    }

    @Test
    void confirmationEscapesXmlAndListsPeriods() {
        Deal d = TestData.gasSwap().dealTrackingNum(7).tranNum(70).externalRef("A&B<1>").build();
        String xml = new ConfirmationWriter().toXml(d, "BOOK");
        assertTrue(xml.contains("<ExternalRef>A&amp;B&lt;1&gt;</ExternalRef>"));
        assertTrue(xml.contains("<Buyer>DESK</Buyer>"));
        assertTrue(xml.contains("<Seller>CPTY_A</Seller>"));
        assertEquals(2, xml.split("<Period ").length - 1);
    }

    @Test
    void glExtractIsBalanced() {
        MarketData md = TestData.flatMarket(4.0, 0.0);
        Cashflow cf1 = new Cashflow(1, "A", "DESK", YearMonth.of(2026, 9), LocalDate.of(2026, 10, 20), "USD", 1, 1, 1234.56, Cashflow.Type.FINANCIAL);
        Cashflow cf2 = new Cashflow(2, "A", "DESK", YearMonth.of(2026, 9), LocalDate.of(2026, 10, 20), "USD", 1, 1, -99.99, Cashflow.Type.COMMODITY);
        List<GlPostingExtract.JournalLine> lines = new GlPostingExtract().build(TestData.VAL_DATE, List.of(cf1, cf2),
                List.of(new ValuationEngine().value(TestData.gasSwap().build(), md),
                        new ValuationEngine().value(TestData.gasSwap().buySell(BuySell.SELL).build(), md)));
        assertEquals(8, lines.size());
        assertTrue(GlPostingExtract.isBalanced(lines));
        assertTrue(new GlPostingExtract().toCsv(lines).startsWith("posting_date,"));
    }

    @Test
    void reconciliationReportsBreaksBothSides() {
        Deal fut = TestData.gasSwap().toolset(Toolset.COM_FUT).quantity(10_000).status(TranStatus.VALIDATED)
                .startDate(LocalDate.of(2026, 12, 1)).endDate(LocalDate.of(2026, 12, 31)).build();
        Map<PositionReconciliation.Key, Double> internal = PositionReconciliation.internalFuturesPositions(List.of(fut));
        PositionReconciliation.Key dec = new PositionReconciliation.Key("HH", YearMonth.of(2026, 12));
        PositionReconciliation.Key jan = new PositionReconciliation.Key("HH", YearMonth.of(2027, 1));
        List<PositionReconciliation.Break> breaks = new PositionReconciliation(0.5)
                .reconcile(internal, Map.of(dec, 10_000.0, jan, -5_000.0));
        assertEquals(1, breaks.size());
        assertEquals(jan, breaks.get(0).key());
        assertEquals(5_000, breaks.get(0).difference(), 1e-9);
    }

    @Test
    void dispatcherEmitsConfirmationsOnceAndMarksProcessed() {
        EndurSession session = TestData.session();
        MarketData md = TestData.flatMarket(3.60, 0.0);
        TradeLifecycleService lifecycle = new TradeLifecycleService(session, StandardRules.defaultEngine(),
                new OpsContext(session, md, new ValuationEngine()));
        long id = lifecycle.book(TestData.gasSwap().build()).deal().dealTrackingNum();
        lifecycle.validate(id);
        lifecycle.amend(id, b -> b.quantity(1500), "test");

        TradeEventDispatcher dispatcher = new TradeEventDispatcher(session, new ConfirmationWriter());
        List<TradeEventDispatcher.Dispatched> first = dispatcher.dispatchPending();
        assertEquals(2, first.size()); // BOOK and AMEND; VALIDATE is internal only
        assertTrue(first.get(1).document().contains("<Quantity>1500.00</Quantity>"));
        assertTrue(dispatcher.dispatchPending().isEmpty());
        assertTrue(session.userTable(TradeLifecycleService.EVENTS_TABLE).stream()
                .allMatch(r -> Boolean.TRUE.equals(r.get("processed"))));
    }
}
