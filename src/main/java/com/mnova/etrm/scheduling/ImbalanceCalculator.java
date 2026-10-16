package com.mnova.etrm.scheduling;

import java.time.LocalDate;

/**
 * Pipeline imbalance and cash-out.
 *
 * <p>After flow, measured (actual) receipts and deliveries rarely equal what was scheduled.
 * The shipper's imbalance is actual receipts net of fuel minus actual deliveries:
 * positive means the shipper left gas on the pipe (long), negative means it took more than it put in (short).</p>
 *
 * <p>Imbalances within the tolerance band are carried forward at no penalty. Beyond it they are
 * cashed out at the index price adjusted by a tiered factor: a long shipper is paid less than index,
 * a short shipper pays more than index - so being out of balance always costs money.</p>
 */
public final class ImbalanceCalculator {

    public record Input(String pipeline, LocalDate gasDay, double scheduledDelivery,
                        double actualReceipt, double actualDelivery, double fuelRate) {
    }

    public record Result(String pipeline, LocalDate gasDay, double imbalance, double percent,
                         double tolerated, double cashoutVolume, double cashoutAmount, String tier) {
        public String direction() {
            return imbalance > 0 ? "LONG" : imbalance < 0 ? "SHORT" : "FLAT";
        }
    }

    private final double tolerance;
    private final double tier2Threshold;

    /**
     * @param tolerance      no-penalty band as a fraction of scheduled delivery (e.g. 0.05)
     * @param tier2Threshold beyond this fraction the higher penalty tier applies (e.g. 0.10)
     */
    public ImbalanceCalculator(double tolerance, double tier2Threshold) {
        if (tolerance < 0 || tier2Threshold < tolerance) {
            throw new IllegalArgumentException("require 0 <= tolerance <= tier2Threshold");
        }
        this.tolerance = tolerance;
        this.tier2Threshold = tier2Threshold;
    }

    public Result calculate(Input in, double indexPrice) {
        double imbalance = in.actualReceipt() * (1 - in.fuelRate()) - in.actualDelivery();
        double base = Math.max(in.scheduledDelivery(), 1.0);
        double pct = Math.abs(imbalance) / base;
        double band = tolerance * base;
        if (Math.abs(imbalance) <= band) {
            return new Result(in.pipeline(), in.gasDay(), imbalance, pct, imbalance, 0, 0, "WITHIN_TOLERANCE");
        }
        double tolerated = Math.signum(imbalance) * band;
        double outside = imbalance - tolerated;
        String tier = pct > tier2Threshold ? "TIER_2" : "TIER_1";
        double factor;
        if (imbalance > 0) {
            factor = pct > tier2Threshold ? 0.80 : 0.90;   // pipeline buys excess gas below index
        } else {
            factor = pct > tier2Threshold ? 1.25 : 1.10;   // shipper buys shortfall above index
        }
        // Positive cash-out amount = shipper receives; negative = shipper pays.
        double amount = outside * indexPrice * factor;
        return new Result(in.pipeline(), in.gasDay(), imbalance, pct, tolerated, outside, amount, tier);
    }
}
