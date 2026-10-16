package com.mnova.etrm;

import com.mnova.etrm.domain.BuySell;
import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.DeliveryPeriod;
import com.mnova.etrm.domain.Toolset;
import com.mnova.etrm.domain.TranStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainTest {

    @Test
    void periodsAreClippedToDealDatesAndVolumeIsDailyQuantityTimesDays() {
        Deal d = TestData.gasSwap().startDate(LocalDate.of(2026, 11, 16)).endDate(LocalDate.of(2027, 1, 10)).build();
        List<DeliveryPeriod> p = d.periods();
        assertEquals(3, p.size());
        assertEquals(LocalDate.of(2026, 11, 16), p.get(0).start());
        assertEquals(15_000, p.get(0).volume(), 1e-9);   // 15 days
        assertEquals(31_000, p.get(1).volume(), 1e-9);   // December
        assertEquals(10_000, p.get(2).volume(), 1e-9);   // 10 days of January
        assertEquals(56_000, d.totalVolume(), 1e-9);
    }

    @Test
    void futuresVolumeIsQuantityPerMonth() {
        Deal f = TestData.gasSwap().toolset(Toolset.COM_FUT).quantity(10_000)
                .startDate(LocalDate.of(2026, 12, 1)).endDate(LocalDate.of(2026, 12, 31)).build();
        assertEquals(10_000, f.totalVolume(), 1e-9);
    }

    @Test
    void paymentDateIsTwentiethOfFollowingMonth() {
        DeliveryPeriod p = new DeliveryPeriod(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), 1);
        assertEquals(LocalDate.of(2027, 1, 20), p.paymentDate());
        assertEquals(YearMonth.of(2026, 12), p.month());
    }

    @Test
    void rejectsEndBeforeStart() {
        assertThrows(IllegalArgumentException.class, () ->
                TestData.gasSwap().startDate(LocalDate.of(2027, 1, 1)).endDate(LocalDate.of(2026, 1, 1)).build());
    }

    @Test
    void statusTransitions() {
        assertTrue(TranStatus.PENDING.canMoveTo(TranStatus.NEW));
        assertTrue(TranStatus.VALIDATED.canMoveTo(TranStatus.AMENDED));
        assertTrue(TranStatus.AMENDED.canMoveTo(TranStatus.AMENDED));
        assertFalse(TranStatus.CANCELLED.canMoveTo(TranStatus.NEW));
        assertFalse(TranStatus.NEW.canMoveTo(TranStatus.MATURED));
        assertTrue(TranStatus.VALIDATED.isLive());
        assertFalse(TranStatus.MATURED.isLive());
    }

    @Test
    void buySellParsing() {
        assertEquals(BuySell.BUY, BuySell.parse(" b "));
        assertEquals(BuySell.SELL, BuySell.parse("Sell"));
        assertThrows(IllegalArgumentException.class, () -> BuySell.parse("X"));
    }
}
