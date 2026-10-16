package com.mnova.etrm;

import com.mnova.etrm.domain.BuySell;
import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Toolset;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.scheduling.DealLocation;
import com.mnova.etrm.scheduling.ImbalanceCalculator;
import com.mnova.etrm.scheduling.Location;
import com.mnova.etrm.scheduling.Nomination;
import com.mnova.etrm.scheduling.NominationCycle;
import com.mnova.etrm.scheduling.NominationService;
import com.mnova.etrm.scheduling.ScheduleBuilder;
import com.mnova.etrm.scheduling.TransportContract;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulingTest {

    private static final LocalDate GAS_DAY = LocalDate.of(2026, 11, 1);
    private static final List<Location> POINTS = List.of(
            new Location("R1", "PIPE", Location.Type.RECEIPT),
            new Location("D1", "PIPE", Location.Type.DELIVERY),
            new Location("D2", "PIPE", Location.Type.DELIVERY));
    private static final TransportContract FT = new TransportContract("FT", "PIPE", TransportContract.ServiceType.FIRM, 5_000, 0.02, 0.10);
    private static final TransportContract IT = new TransportContract("IT", "PIPE", TransportContract.ServiceType.INTERRUPTIBLE, 5_000, 0.02, 0.20);

    private static Deal gas(long id, BuySell bs, double qty) {
        return TestData.gasSwap().dealTrackingNum(id).toolset(Toolset.PHYS_GAS).buySell(bs).quantity(qty)
                .status(TranStatus.VALIDATED).startDate(GAS_DAY).endDate(GAS_DAY.plusDays(29)).build();
    }

    private ScheduleBuilder builder() {
        return new ScheduleBuilder(POINTS, List.of(IT, FT)); // deliberately unordered: FT must still be used first
    }

    @Test
    void grossesUpForFuelAndUsesFirmBeforeInterruptible() {
        ScheduleBuilder.ScheduleResult r = builder().build(GAS_DAY, NominationCycle.TIMELY,
                List.of(gas(1, BuySell.BUY, 10_000), gas(2, BuySell.SELL, 4_900), gas(3, BuySell.SELL, 3_000)),
                List.of(new DealLocation(1, "R1"), new DealLocation(2, "D1"), new DealLocation(3, "D2")));

        // Deal 2 consumes all 5,000 of firm capacity; deal 3 goes on interruptible.
        assertEquals(2, r.nominations().size());
        Nomination first = r.nominations().get(0);
        assertEquals("FT", first.contractId());
        assertEquals(5_000, first.receiptVolume(), 1e-9);   // 4,900 / 0.98 = 5,000
        assertEquals(4_900, first.deliveryVolume(), 1e-9);
        Nomination second = r.nominations().get(1);
        assertEquals("IT", second.contractId());
        assertEquals(3_062, second.receiptVolume(), 1e-9);  // ceil(3,000 / 0.98)
        assertEquals(3_000, second.deliveryVolume(), 1e-9);
        assertEquals(1_938, r.unmatchedSupply().get(1L), 1e-9);
        assertTrue(r.unmatchedMarket().isEmpty());
    }

    @Test
    void reportsCapacityConstraintAndUnmatchedMarket() {
        ScheduleBuilder b = new ScheduleBuilder(POINTS, List.of(FT));
        ScheduleBuilder.ScheduleResult r = b.build(GAS_DAY, NominationCycle.TIMELY,
                List.of(gas(1, BuySell.BUY, 20_000), gas(2, BuySell.SELL, 8_000)),
                List.of(new DealLocation(1, "R1"), new DealLocation(2, "D1")));
        assertEquals(5_000, r.totalReceipt(), 1e-9);
        assertEquals(8_000 - 4_900, r.unmatchedMarket().get(2L), 1e-9);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("capacity constrained")));
    }

    @Test
    void skipsDealsNotFlowingOrWronglyLocated() {
        Deal notFlowing = gas(4, BuySell.BUY, 1_000).toBuilder().startDate(GAS_DAY.plusMonths(1)).endDate(GAS_DAY.plusMonths(2)).build();
        Deal cancelled = gas(5, BuySell.BUY, 1_000).toBuilder().status(TranStatus.CANCELLED).build();
        Deal sellAtReceipt = gas(6, BuySell.SELL, 1_000);
        Deal noLocation = gas(7, BuySell.BUY, 1_000);
        ScheduleBuilder.ScheduleResult r = builder().build(GAS_DAY, NominationCycle.TIMELY,
                List.of(notFlowing, cancelled, sellAtReceipt, noLocation),
                List.of(new DealLocation(4, "R1"), new DealLocation(5, "R1"), new DealLocation(6, "R1")));
        assertTrue(r.nominations().isEmpty());
        assertEquals(2, r.warnings().size());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("deal 6") && w.contains("expected DELIVERY")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("deal 7") && w.contains("no valid delivery location")));
    }

    @Test
    void cycleDeadlines() {
        assertEquals(LocalDateTime.of(2026, 10, 31, 13, 0), NominationCycle.TIMELY.deadline(GAS_DAY));
        assertEquals(LocalDateTime.of(2026, 11, 1, 19, 0), NominationCycle.INTRADAY_3.deadline(GAS_DAY));
        assertEquals(NominationCycle.EVENING, NominationCycle.nextOpen(GAS_DAY, LocalDateTime.of(2026, 10, 31, 14, 0)));
        assertEquals(NominationCycle.INTRADAY_2, NominationCycle.nextOpen(GAS_DAY, LocalDateTime.of(2026, 11, 1, 12, 0)));
        assertEquals(null, NominationCycle.nextOpen(GAS_DAY, LocalDateTime.of(2026, 11, 1, 20, 0)));
    }

    @Test
    void lateSubmissionsAreRejectedWithNextCycle() {
        Nomination n = new Nomination("N1", GAS_DAY, NominationCycle.TIMELY, "PIPE", "FT", "R1", "D1", 1, 2,
                1000, 980, 0, Nomination.Status.DRAFT);
        NominationService svc = new NominationService();
        NominationService.SubmitResult ok = svc.submit(List.of(n), NominationCycle.TIMELY, LocalDateTime.of(2026, 10, 31, 12, 59));
        assertEquals(Nomination.Status.SUBMITTED, ok.accepted().get(0).status());
        NominationService.SubmitResult late = svc.submit(List.of(n), NominationCycle.TIMELY, LocalDateTime.of(2026, 10, 31, 13, 1));
        assertTrue(late.accepted().isEmpty());
        assertTrue(late.rejections().get(0).contains("next open cycle is EVENING"));
    }

    @Test
    void constraintCutsInterruptibleFirstThenFirmProRata() {
        Nomination f1 = new Nomination("F1", GAS_DAY, NominationCycle.TIMELY, "PIPE", "FT", "R1", "D1", 1, 2, 3000, 2940, 0, Nomination.Status.SUBMITTED);
        Nomination f2 = new Nomination("F2", GAS_DAY, NominationCycle.TIMELY, "PIPE", "FT", "R1", "D2", 1, 3, 1000, 980, 0, Nomination.Status.SUBMITTED);
        Nomination i1 = new Nomination("I1", GAS_DAY, NominationCycle.TIMELY, "PIPE", "IT", "R1", "D2", 1, 3, 2000, 1960, 0, Nomination.Status.SUBMITTED);
        Nomination other = new Nomination("O1", GAS_DAY, NominationCycle.TIMELY, "PIPE", "IT", "R9", "D2", 1, 3, 500, 490, 0, Nomination.Status.SUBMITTED);
        NominationService svc = new NominationService();
        Map<String, TransportContract> contracts = Map.of("FT", FT, "IT", IT);

        Map<String, Double> partialIt = svc.allocateConstraint(List.of(f1, f2, i1, other), "R1", 5_000, contracts::get);
        assertEquals(3000, partialIt.get("F1"), 1e-9);
        assertEquals(1000, partialIt.get("F2"), 1e-9);
        assertEquals(1000, partialIt.get("I1"), 1e-9);
        assertEquals(500, partialIt.get("O1"), 1e-9);    // other point unaffected

        Map<String, Double> firmCut = svc.allocateConstraint(List.of(f1, f2, i1), "R1", 2_001, contracts::get);
        assertEquals(0, firmCut.get("I1"), 1e-9);
        assertEquals(2_001, firmCut.get("F1") + firmCut.get("F2"), 1e-9);
        assertEquals(1501, firmCut.get("F1"), 1e-9);     // 1500 pro rata + 1 rounding unit to the largest
        assertEquals(500, firmCut.get("F2"), 1e-9);

        List<Nomination> confirmed = svc.confirm(List.of(f1, f2, i1), partialIt);
        assertEquals(Nomination.Status.SCHEDULED, confirmed.get(0).status());
        assertEquals(Nomination.Status.CUT, confirmed.get(2).status());
        assertEquals(1000, confirmed.get(2).cutVolume(), 1e-9);
        assertEquals(1960 - 980, svc.marketShortfall(confirmed, contracts::get).get(3L), 1e-9);
        assertEquals(4000 * 0.10 + 1000 * 0.20, svc.transportCost(confirmed, contracts::get), 1e-9);
    }

    @Test
    void imbalanceToleranceAndTieredCashout() {
        ImbalanceCalculator calc = new ImbalanceCalculator(0.05, 0.10);
        // within tolerance: 10,000 * 0.98 - 9,700 = +100 on 10,000 scheduled
        ImbalanceCalculator.Result ok = calc.calculate(new ImbalanceCalculator.Input("PIPE", GAS_DAY, 10_000, 10_000, 9_700, 0.02), 3.0);
        assertEquals(100, ok.imbalance(), 1e-9);
        assertEquals("WITHIN_TOLERANCE", ok.tier());
        assertEquals(0, ok.cashoutAmount(), 1e-12);

        // short 800 (8%): 300 outside band at 1.10 x index
        ImbalanceCalculator.Result t1 = calc.calculate(new ImbalanceCalculator.Input("PIPE", GAS_DAY, 10_000, 10_000, 10_600, 0.02), 3.0);
        assertEquals("SHORT", t1.direction());
        assertEquals("TIER_1", t1.tier());
        assertEquals(-300 * 3.0 * 1.10, t1.cashoutAmount(), 1e-6);

        // long 1,500 (15%): 1,000 outside band at 0.80 x index
        ImbalanceCalculator.Result t2 = calc.calculate(new ImbalanceCalculator.Input("PIPE", GAS_DAY, 10_000, 11_000, 9_280, 0.02), 3.0);
        assertEquals("LONG", t2.direction());
        assertEquals("TIER_2", t2.tier());
        assertEquals(1_000 * 3.0 * 0.80, t2.cashoutAmount(), 1e-6);

        assertThrows(IllegalArgumentException.class, () -> new ImbalanceCalculator(0.2, 0.1));
    }

    @Test
    void contractValidation() {
        assertThrows(IllegalArgumentException.class, () -> new TransportContract("X", "P", TransportContract.ServiceType.FIRM, 100, 1.0, 0));
        assertEquals(980, FT.delivered(1000), 1e-9);
        assertEquals(1000, FT.requiredReceipt(980), 1e-9);
    }
}
