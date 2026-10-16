package com.mnova.etrm.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * One monthly delivery/calculation period of a deal (an Endur "profile" period).
 * {@code volume} is the total quantity for the period, unsigned.
 */
public record DeliveryPeriod(LocalDate start, LocalDate end, double volume) {

    public DeliveryPeriod {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("Period end before start: " + start + " > " + end);
        }
    }

    public YearMonth month() {
        return YearMonth.from(start);
    }

    /** Payment date: by convention 20th of the month following delivery. */
    public LocalDate paymentDate() {
        return YearMonth.from(end).plusMonths(1).atDay(20);
    }

    public boolean isRealized(LocalDate asOf) {
        return end.isBefore(asOf);
    }
}
