package com.mnova.etrm.valuation;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Valuation of a single monthly period.
 *
 * @param signedVolume +volume for long, -volume for short
 * @param price        fixing (realized) or forward price (unrealized)
 * @param undiscounted signedVolume x (price - fixedPrice)
 * @param pv           undiscounted x discount factor
 * @param delta        dPV/dPrice = signedVolume x discount factor (zero once realized)
 */
public record PeriodValue(
        YearMonth month,
        double signedVolume,
        double price,
        double fixedPrice,
        LocalDate paymentDate,
        double discountFactor,
        double undiscounted,
        double pv,
        double delta,
        boolean realized) {
}
