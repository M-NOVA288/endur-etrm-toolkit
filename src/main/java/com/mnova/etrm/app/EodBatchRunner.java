package com.mnova.etrm.app;

import com.mnova.etrm.adapter.EndurSession;
import com.mnova.etrm.domain.Deal;
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
import com.mnova.etrm.risk.CreditExposure;
import com.mnova.etrm.risk.HistoricalVaR;
import com.mnova.etrm.risk.PositionReport;
import com.mnova.etrm.scheduling.DealLocation;
import com.mnova.etrm.scheduling.ImbalanceCalculator;
import com.mnova.etrm.scheduling.Nomination;
import com.mnova.etrm.scheduling.NominationCycle;
import com.mnova.etrm.scheduling.NominationService;
import com.mnova.etrm.scheduling.ScheduleBuilder;
import com.mnova.etrm.settlement.Cashflow;
import com.mnova.etrm.settlement.CashflowGenerator;
import com.mnova.etrm.settlement.InvoiceService;
import com.mnova.etrm.valuation.PnlExplain;
import com.mnova.etrm.valuation.ValuationEngine;
import com.mnova.etrm.valuation.ValuationResult;

import java.io.FileReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-of-day batch, wiring every component together the way an Endur EOD workflow would:
 * trade feed load -> ops validation -> confirmations -> valuation -> positions/VaR/credit
 * -> settlements/invoices -> GL extract -> broker reconciliation -> maturity -> P&amp;L explain
 * -> gas scheduling (nominations, pipeline confirmations, imbalances).
 *
 * <p>Usage: {@code java -jar endur-etrm-toolkit.jar [trades.csv]} (defaults to the bundled sample).</p>
 */
public final class EodBatchRunner {

    public static void main(String[] args) throws Exception {
        EndurSession session = SampleEnvironment.session();
        MarketData md = SampleEnvironment.marketData();
        ValuationEngine engine = new ValuationEngine();
        OpsContext ctx = new OpsContext(session, md, engine);
        TradeLifecycleService lifecycle = new TradeLifecycleService(session, StandardRules.defaultEngine(), ctx);

        banner("EOD BATCH  valuation date " + md.valuationDate());

        // 1. Inbound trade feed -------------------------------------------------------------
        TradeFeedParser.ParseResult feed;
        try (Reader r = args.length > 0
                ? new FileReader(args[0], StandardCharsets.UTF_8)
                : SampleEnvironment.resource("/sample/trades.csv")) {
            feed = new TradeFeedParser().parse(r);
        }
        section("1. Trade feed: " + feed.drafts().size() + " parsed, " + feed.rejections().size() + " rejected");
        feed.rejections().forEach(x -> System.out.printf("  REJECT line %d %s: %s%n", x.line(), x.extRef(), x.reason()));

        // 2. Booking through ops-service rules ------------------------------------------------
        section("2. Booking (pre-process ops rules)");
        List<Long> booked = new ArrayList<>();
        for (Deal draft : feed.drafts()) {
            TradeLifecycleService.Result res = lifecycle.book(draft);
            if (res.success()) {
                booked.add(res.deal().dealTrackingNum());
                System.out.printf("  BOOKED  %-12s -> deal %d  %s%n", draft.externalRef(), res.deal().dealTrackingNum(),
                        res.outcome().issues().isEmpty() ? "" : "[" + res.message() + "]");
            } else {
                System.out.printf("  BLOCKED %-12s %s%n", draft.externalRef(), res.message());
            }
        }
        booked.forEach(lifecycle::validate);

        // 3. Amendment of a live deal --------------------------------------------------------
        section("3. Amendment");
        long amendTarget = booked.get(0);
        TradeLifecycleService.Result amend = lifecycle.amend(amendTarget, b -> b.quantity(12_000), "volume increase per trader");
        System.out.println("  " + (amend.success() ? "AMENDED " + amend.deal() : "FAILED " + amend.message()));
        if (amend.success()) {
            lifecycle.validate(amendTarget);
        }
        session.auditTrail(amendTarget).forEach(a -> System.out.println("    audit: " + a));

        // 4. Confirmations -------------------------------------------------------------------
        List<TradeEventDispatcher.Dispatched> confs = new TradeEventDispatcher(session, new ConfirmationWriter()).dispatchPending();
        section("4. Confirmations generated: " + confs.size());
        if (!confs.isEmpty()) {
            System.out.println(indent(confs.get(confs.size() - 1).document()));
        }

        // 5. Valuation -----------------------------------------------------------------------
        List<Deal> live = session.query(d -> d.status().isLive());
        ValuationEngine.BatchResult valued = engine.valueAll(live, md);
        section("5. Valuation");
        System.out.printf("  %-8s %-11s %-6s %-14s %16s %16s%n", "Deal", "Toolset", "B/S", "Portfolio", "Unrealized MtM", "Realized");
        for (ValuationResult v : valued.results()) {
            System.out.printf("  %-8d %-11s %-6s %-14s %,16.0f %,16.0f%n", v.deal().dealTrackingNum(), v.deal().toolset(),
                    v.deal().buySell(), v.deal().portfolio(), v.unrealizedMtm(), v.realizedValue());
        }
        System.out.printf("  Total unrealized MtM: %,.0f   valuation errors: %d%n", valued.totalUnrealized(), valued.errors().size());

        // 6. Risk ----------------------------------------------------------------------------
        section("6. Risk");
        PositionReport positions = PositionReport.build(valued.results());
        System.out.print(indent(positions.render()));
        double var = new HistoricalVaR(0.95, 1).compute(positions.deltaByIndex(), md);
        System.out.printf("  1-day 95%% historical VaR: %,.0f%n", var);
        System.out.println("  Credit exposure:");
        for (CreditExposure.Line l : CreditExposure.report(valued.results(), session.parties())) {
            System.out.printf("    %-18s net %,14.0f  exposure %,14.0f  limit %,14.0f  util %5.1f%% %s%n", l.counterparty(),
                    l.netValue(), l.exposure(), l.limit(), l.utilization() * 100, l.breached() ? "BREACH" : "");
        }

        // 7. Settlements ---------------------------------------------------------------------
        section("7. Settlements / invoices");
        List<Deal> settleable = session.query(d -> d.status() != TranStatus.CANCELLED);
        List<Cashflow> cashflows = new CashflowGenerator().generate(settleable, md);
        List<InvoiceService.Invoice> invoices = new InvoiceService(Set.of("LONESTAR_POWER")).build(cashflows);
        for (InvoiceService.Invoice inv : invoices) {
            System.out.printf("  %s %-18s %s %-7s %,14.2f  (%d lines)%n", inv.paymentDate(), inv.counterparty(),
                    inv.currency(), inv.direction(), Math.abs(inv.netAmount()), inv.lines().size());
        }

        // 8. GL extract ----------------------------------------------------------------------
        GlPostingExtract gl = new GlPostingExtract();
        List<GlPostingExtract.JournalLine> journal = gl.build(md.valuationDate(), cashflows, valued.results());
        section("8. GL extract: " + journal.size() + " journal lines, balanced=" + GlPostingExtract.isBalanced(journal));

        // 9. Broker reconciliation -----------------------------------------------------------
        section("9. Futures reconciliation vs clearing broker");
        Map<PositionReconciliation.Key, Double> internal = PositionReconciliation.internalFuturesPositions(live);
        Map<PositionReconciliation.Key, Double> broker = Map.of(
                new PositionReconciliation.Key("WTI_NYMEX", YearMonth.of(2026, 12)), 49_000.0);
        List<PositionReconciliation.Break> breaks = new PositionReconciliation(1.0).reconcile(internal, broker);
        if (breaks.isEmpty()) {
            System.out.println("  no breaks");
        }
        breaks.forEach(b -> System.out.printf("  BREAK %s %s internal %,.0f broker %,.0f diff %,.0f%n",
                b.key().index(), b.key().month(), b.internal(), b.external(), b.difference()));

        // 10. Maturity -----------------------------------------------------------------------
        List<Deal> matured = lifecycle.matureExpired(md.valuationDate());
        section("10. Matured deals: " + matured.stream().map(d -> String.valueOf(d.dealTrackingNum())).toList());

        // 11. P&L explain (prior day = curves 2% lower, futures deal booked today) ------------
        section("11. P&L explain vs prior day");
        MarketData prior = md.copyTo(md.valuationDate().minusDays(1));
        for (String idx : md.indices()) {
            prior = prior.withShiftedCurve(idx, -0.02 * md.curve(idx).price(YearMonth.from(md.valuationDate())));
        }
        List<Deal> current = session.query(d -> d.status().isLive());
        List<Deal> priorDeals = current.stream().filter(d -> !"CME-9001234".equals(d.externalRef())).toList();
        PnlExplain.Explain ex = new PnlExplain(engine).explain(priorDeals, current, prior, md);
        System.out.printf("  new deals %,14.0f%n  time      %,14.0f%n  market    %,14.0f%n  total     %,14.0f%n",
                ex.newDeals(), ex.time(), ex.market(), ex.total());

        // 12. Gas scheduling for the first November gas day --------------------------------
        runScheduling(session);

        banner("EOD COMPLETE");
    }

    private static void runScheduling(EndurSession session) {
        LocalDate gasDay = LocalDate.of(2026, 11, 1);
        section("12. Gas scheduling  gas day " + gasDay + " (pipeline GULF_STAR)");
        List<DealLocation> locations = new ArrayList<>();
        SampleEnvironment.pointByExternalRef().forEach((ref, point) ->
                session.findByExternalRef(ref).ifPresent(d -> locations.add(new DealLocation(d.dealTrackingNum(), point))));

        ScheduleBuilder builder = new ScheduleBuilder(SampleEnvironment.pipelinePoints(), SampleEnvironment.transportContracts());
        ScheduleBuilder.ScheduleResult schedule = builder.build(gasDay, NominationCycle.TIMELY,
                session.query(d -> d.status().isLive()), locations);
        System.out.printf("  %-24s %-10s %-10s %-16s %8s %8s %10s%n", "Nomination", "Contract", "Receipt", "Delivery",
                "Supply", "Market", "Rec/Del");
        for (Nomination n : schedule.nominations()) {
            System.out.printf("  %-24s %-10s %-10s %-16s %8d %8d %,5.0f/%,5.0f%n", n.id(), n.contractId(), n.receiptPoint(),
                    n.deliveryPoint(), n.supplyDeal(), n.marketDeal(), n.receiptVolume(), n.deliveryVolume());
        }
        schedule.unmatchedSupply().forEach((deal, v) -> System.out.printf("  Unmatched supply: deal %d %,.0f MMBtu (sell spot or store)%n", deal, v));
        schedule.unmatchedMarket().forEach((deal, v) -> System.out.printf("  Unmatched market: deal %d %,.0f MMBtu (buy spot)%n", deal, v));
        schedule.warnings().forEach(w -> System.out.println("  WARN " + w));

        NominationService noms = new NominationService();
        NominationService.SubmitResult late = noms.submit(schedule.nominations(), NominationCycle.TIMELY,
                LocalDateTime.of(2026, 10, 31, 13, 5));
        System.out.println("  Submit at 13:05 for Timely: " + late.rejections().size() + " rejected, e.g. "
                + (late.rejections().isEmpty() ? "-" : late.rejections().get(0)));
        NominationService.SubmitResult sub = noms.submit(schedule.nominations(), NominationCycle.TIMELY,
                LocalDateTime.of(2026, 10, 31, 11, 30));
        System.out.println("  Submit at 11:30 for Timely: " + sub.accepted().size() + " accepted");

        Map<String, Double> conf = noms.allocateConstraint(sub.accepted(), "GS-R-KATY", 11_000, builder::contract);
        List<Nomination> confirmed = noms.confirm(sub.accepted(), conf);
        System.out.println("  Pipeline constraint at GS-R-KATY: 11,000 available");
        for (Nomination n : confirmed) {
            System.out.printf("    %-24s %-9s scheduled %,8.0f of %,8.0f%s%n", n.id(), n.status(), n.scheduledVolume(),
                    n.receiptVolume(), n.cutVolume() > 0 ? String.format("  cut %,.0f", n.cutVolume()) : "");
        }
        noms.marketShortfall(confirmed, builder::contract).forEach((deal, v) ->
                System.out.printf("  Re-nominate: market deal %d short %,.0f MMBtu -> source replacement supply in EVENING cycle%n", deal, v));
        System.out.printf("  Transport cost: $%,.2f%n", noms.transportCost(confirmed, builder::contract));

        ImbalanceCalculator imb = new ImbalanceCalculator(0.05, 0.10);
        double scheduledDelivery = confirmed.stream()
                .mapToDouble(n -> Math.floor(builder.contract(n.contractId()).delivered(n.scheduledVolume()))).sum();
        System.out.println("  Imbalances (index $3.40):");
        for (double[] actual : new double[][]{{10_950, 10_700}, {10_950, 12_100}}) {
            ImbalanceCalculator.Result r = imb.calculate(new ImbalanceCalculator.Input("GULF_STAR", gasDay,
                    scheduledDelivery, actual[0], actual[1], 0.015), 3.40);
            System.out.printf("    actual rec %,.0f del %,.0f -> %s %,.0f (%.1f%%) %s cash-out %,.2f%n", actual[0], actual[1],
                    r.direction(), Math.abs(r.imbalance()), r.percent() * 100, r.tier(), r.cashoutAmount());
        }
    }

    private static void banner(String s) {
        System.out.println("=".repeat(78));
        System.out.println(s);
        System.out.println("=".repeat(78));
    }

    private static void section(String s) {
        System.out.println();
        System.out.println("-- " + s);
    }

    private static String indent(String s) {
        return s.lines().map(l -> "  " + l).reduce((a, b) -> a + "\n" + b).orElse("") + "\n";
    }
}
