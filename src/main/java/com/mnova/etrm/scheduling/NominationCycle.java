package com.mnova.etrm.scheduling;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * NAESB standard nomination cycles for a gas day (deadlines in Central Clock Time).
 * Timely and Evening are submitted the day before the gas day; the intraday cycles
 * on the gas day itself. The gas day runs 9:00 AM to 9:00 AM CCT.
 */
public enum NominationCycle {
    TIMELY(-1, LocalTime.of(13, 0), false),
    EVENING(-1, LocalTime.of(18, 0), false),
    INTRADAY_1(0, LocalTime.of(10, 0), true),
    INTRADAY_2(0, LocalTime.of(14, 30), true),
    INTRADAY_3(0, LocalTime.of(19, 0), true);

    private final int dayOffset;
    private final LocalTime deadline;
    private final boolean intraday;

    NominationCycle(int dayOffset, LocalTime deadline, boolean intraday) {
        this.dayOffset = dayOffset;
        this.deadline = deadline;
        this.intraday = intraday;
    }

    public LocalDateTime deadline(LocalDate gasDay) {
        return gasDay.plusDays(dayOffset).atTime(deadline);
    }

    /**
     * Intraday cycles cannot "bump" (displace) interruptible gas already scheduled in earlier
     * cycles, so increases in intraday cycles are only accepted up to unused capacity.
     */
    public boolean isIntraday() {
        return intraday;
    }

    /** The earliest cycle whose deadline has not passed at {@code now}, or null if none. */
    public static NominationCycle nextOpen(LocalDate gasDay, LocalDateTime now) {
        for (NominationCycle c : values()) {
            if (!now.isAfter(c.deadline(gasDay))) {
                return c;
            }
        }
        return null;
    }
}
