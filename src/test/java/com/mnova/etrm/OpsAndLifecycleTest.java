package com.mnova.etrm;

import com.mnova.etrm.adapter.EndurSession;
import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.lifecycle.TradeLifecycleService;
import com.mnova.etrm.market.MarketData;
import com.mnova.etrm.ops.OpsContext;
import com.mnova.etrm.ops.OpsServiceEngine;
import com.mnova.etrm.ops.RuleResult;
import com.mnova.etrm.ops.StandardRules;
import com.mnova.etrm.ops.TradeOpsRule;
import com.mnova.etrm.ops.rules.CreditLimitRule;
import com.mnova.etrm.ops.rules.MandatoryFieldsRule;
import com.mnova.etrm.ops.rules.PriceToleranceRule;
import com.mnova.etrm.valuation.ValuationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsAndLifecycleTest {

    private EndurSession session;
    private MarketData md;
    private OpsContext ctx;
    private TradeLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        session = TestData.session();
        md = TestData.flatMarket(3.60, 0.0);
        ctx = new OpsContext(session, md, new ValuationEngine());
        lifecycle = new TradeLifecycleService(session, StandardRules.defaultEngine(), ctx);
    }

    @Test
    void bookingAssignsNumbersAndWritesEvent() {
        TradeLifecycleService.Result r = lifecycle.book(TestData.gasSwap().externalRef("X1").build());
        assertTrue(r.success(), r.message());
        assertTrue(r.deal().dealTrackingNum() > 0);
        assertTrue(r.deal().tranNum() > 0);
        assertEquals(TranStatus.NEW, session.current(r.deal().dealTrackingNum()).orElseThrow().status());
        assertEquals(1, session.userTable(TradeLifecycleService.EVENTS_TABLE).size());
        assertEquals("BOOK", session.userTable(TradeLifecycleService.EVENTS_TABLE).get(0).get("action"));
    }

    @Test
    void blockedBookingIsNotPersisted() {
        TradeLifecycleService.Result r = lifecycle.book(TestData.gasSwap().externalBu("CPTY_SUSP").build());
        assertFalse(r.success());
        assertTrue(r.message().contains("suspended"));
        assertTrue(session.query(d -> true).isEmpty());
        assertTrue(session.userTable(TradeLifecycleService.EVENTS_TABLE).isEmpty());
    }

    @Test
    void unknownCounterpartyAndUnauthorizedTraderAreBlocked() {
        assertTrue(lifecycle.book(TestData.gasSwap().externalBu("NOBODY").build()).message().contains("unknown counterparty"));
        assertTrue(lifecycle.book(TestData.gasSwap().portfolio("OTHER_BOOK").build()).message().contains("not authorized"));
    }

    @Test
    void duplicateExternalRefIsBlockedUntilOriginalCancelled() {
        long first = lifecycle.book(TestData.gasSwap().externalRef("DUP-1").build()).deal().dealTrackingNum();
        assertFalse(lifecycle.book(TestData.gasSwap().externalRef("DUP-1").build()).success());
        assertTrue(lifecycle.cancel(first, "booked in error").success());
        assertTrue(lifecycle.book(TestData.gasSwap().externalRef("DUP-1").build()).success());
    }

    @Test
    void amendmentCreatesNewVersionAndKeepsHistory() {
        long id = lifecycle.book(TestData.gasSwap().build()).deal().dealTrackingNum();
        lifecycle.validate(id);
        TradeLifecycleService.Result r = lifecycle.amend(id, b -> b.quantity(2000), "volume change");
        assertTrue(r.success(), r.message());
        List<Deal> history = session.history(id);
        assertEquals(2, history.size());
        assertEquals(1, history.get(0).version());
        assertEquals(2, history.get(1).version());
        assertTrue(history.get(1).tranNum() > history.get(0).tranNum());
        assertEquals(2000, session.current(id).orElseThrow().quantity(), 1e-9);
        assertEquals(TranStatus.AMENDED, session.current(id).orElseThrow().status());
        assertTrue(lifecycle.validate(id).success());
        assertTrue(session.auditTrail(id).stream().anyMatch(a -> a.contains("volume change")));
    }

    @Test
    void amendmentThatBreaksARuleIsRejectedAndOriginalUntouched() {
        long id = lifecycle.book(TestData.gasSwap().build()).deal().dealTrackingNum();
        TradeLifecycleService.Result r = lifecycle.amend(id, b -> b.fixedPrice(36.0), "fat finger");
        assertFalse(r.success());
        assertEquals(1, session.history(id).size());
        assertEquals(3.50, session.current(id).orElseThrow().fixedPrice(), 1e-12);
    }

    @Test
    void illegalTransitionsAreRejected() {
        long id = lifecycle.book(TestData.gasSwap().build()).deal().dealTrackingNum();
        assertTrue(lifecycle.cancel(id, "test").success());
        TradeLifecycleService.Result r = lifecycle.validate(id);
        assertFalse(r.success());
        assertTrue(r.message().contains("illegal status transition"));
    }

    @Test
    void matureExpiredMovesOnlyFinishedValidatedDeals() {
        md.addFixing("HH", java.time.YearMonth.of(2026, 8), 3.0).addFixing("HH", java.time.YearMonth.of(2026, 9), 3.0);
        long done = lifecycle.book(TestData.gasSwap().tradeDate(LocalDate.of(2026, 7, 1)).startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 9, 30)).build()).deal().dealTrackingNum();
        long open = lifecycle.book(TestData.gasSwap().build()).deal().dealTrackingNum();
        lifecycle.validate(done);
        lifecycle.validate(open);
        List<Deal> matured = lifecycle.matureExpired(TestData.VAL_DATE);
        assertEquals(1, matured.size());
        assertEquals(done, matured.get(0).dealTrackingNum());
        assertEquals(TranStatus.VALIDATED, session.current(open).orElseThrow().status());
    }

    @Test
    void mandatoryFieldsListsEveryProblem() {
        Deal d = TestData.gasSwap().index(" ").trader(null).quantity(0).build();
        RuleResult r = new MandatoryFieldsRule().evaluate(d, TranStatus.NEW, ctx);
        assertTrue(r.isBlocking());
        assertTrue(r.message().contains("index"));
        assertTrue(r.message().contains("trader"));
        assertTrue(r.message().contains("quantity"));
    }

    @Test
    void priceToleranceWarnsWithoutCurveAndBlocksOutliers() {
        PriceToleranceRule rule = new PriceToleranceRule(0.10);
        assertEquals(RuleResult.Severity.PASS, rule.evaluate(TestData.gasSwap().build(), TranStatus.NEW, ctx).severity());
        assertTrue(rule.evaluate(TestData.gasSwap().fixedPrice(4.50).build(), TranStatus.NEW, ctx).isBlocking());
        assertEquals(RuleResult.Severity.WARN,
                rule.evaluate(TestData.gasSwap().index("NONE").build(), TranStatus.NEW, ctx).severity());
    }

    @Test
    void creditLimitIncludesExistingNettingSet() {
        // Each long 1000/d Nov-Dec at 2.00 vs 3.60 curve is worth 61,000 x 1.60 = 97,600.
        CreditLimitRule rule = new CreditLimitRule(0.8);
        session.registerParty(com.mnova.etrm.domain.Party.external("CPTY_A", "Counterparty A", 150_000));
        Deal first = TestData.gasSwap().fixedPrice(2.00).build();
        assertEquals(RuleResult.Severity.PASS, rule.evaluate(first, TranStatus.NEW, ctx).severity());
        session.save(first.toBuilder().dealTrackingNum(1).tranNum(1).status(TranStatus.VALIDATED).build());
        Deal second = TestData.gasSwap().dealTrackingNum(2).fixedPrice(2.00).build();
        RuleResult r = rule.evaluate(second, TranStatus.NEW, ctx);
        assertTrue(r.isBlocking(), r.message());
        // An offsetting short reduces exposure and is allowed.
        Deal hedge = TestData.gasSwap().dealTrackingNum(3).buySell(com.mnova.etrm.domain.BuySell.SELL).fixedPrice(3.00).build();
        assertFalse(rule.evaluate(hedge, TranStatus.NEW, ctx).isBlocking());
    }

    @Test
    void ruleThatThrowsIsTreatedAsBlock() {
        TradeOpsRule broken = new TradeOpsRule() {
            public String name() { return "Broken"; }
            public RuleResult evaluate(Deal d, TranStatus t, OpsContext c) { throw new IllegalStateException("boom"); }
        };
        OpsServiceEngine.Outcome o = new OpsServiceEngine(List.of(broken)).run(TestData.gasSwap().build(), TranStatus.NEW, ctx);
        assertTrue(o.blocked());
        assertTrue(o.summary().contains("boom"));
    }
}
