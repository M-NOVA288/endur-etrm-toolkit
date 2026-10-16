package com.mnova.etrm.settlement;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * A settlement cashflow. Positive amount = we receive, negative = we pay.
 *
 * @param type COMMODITY for physical deliveries (full value), FINANCIAL for swap net settlement
 */
public record Cashflow(
        long dealTrackingNum,
        String counterparty,
        String internalBu,
        YearMonth deliveryMonth,
        LocalDate paymentDate,
        String currency,
        double volume,
        double price,
        double amount,
        Type type) {

    public enum Type { COMMODITY, FINANCIAL }
}
