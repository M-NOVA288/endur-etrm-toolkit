package com.mnova.etrm.risk;

import com.mnova.etrm.market.MarketData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Delta-based historical-simulation VaR.
 *
 * <p>For each historical day i, scenario P&amp;L = sum over indices of
 * (net delta of the index x that index's price change on day i). VaR is the loss at the
 * chosen confidence level, scaled by sqrt(holding period). Indices are aligned on the
 * shortest available history so every scenario uses a consistent set of moves.</p>
 */
public final class HistoricalVaR {

    private final double confidence;
    private final int holdingDays;

    public HistoricalVaR(double confidence, int holdingDays) {
        if (confidence <= 0.5 || confidence >= 1.0) {
            throw new IllegalArgumentException("confidence must be in (0.5, 1)");
        }
        if (holdingDays < 1) {
            throw new IllegalArgumentException("holdingDays must be >= 1");
        }
        this.confidence = confidence;
        this.holdingDays = holdingDays;
    }

    public double[] scenarioPnl(Map<String, Double> deltaByIndex, MarketData md) {
        int n = Integer.MAX_VALUE;
        List<String> used = new ArrayList<>();
        for (String idx : deltaByIndex.keySet()) {
            int len = md.history(idx).size();
            if (len == 0) {
                throw new MarketData.MissingMarketDataException("No price history for VaR index " + idx);
            }
            n = Math.min(n, len);
            used.add(idx);
        }
        if (used.isEmpty()) {
            return new double[0];
        }
        double[] pnl = new double[n];
        for (String idx : used) {
            List<Double> h = md.history(idx);
            double delta = deltaByIndex.get(idx);
            int offset = h.size() - n; // most recent n days
            for (int i = 0; i < n; i++) {
                pnl[i] += delta * h.get(offset + i);
            }
        }
        return pnl;
    }

    /** Returns VaR as a positive number representing a loss. */
    public double compute(Map<String, Double> deltaByIndex, MarketData md) {
        double[] pnl = scenarioPnl(deltaByIndex, md);
        if (pnl.length == 0) {
            return 0.0;
        }
        Arrays.sort(pnl);
        // Small epsilon guards against floating-point error, e.g. (1 - 0.9) * 10 = 0.9999999.
        int idx = (int) Math.floor((1.0 - confidence) * pnl.length + 1e-9);
        idx = Math.min(Math.max(idx, 0), pnl.length - 1);
        double oneDay = Math.max(0.0, -pnl[idx]);
        return oneDay * Math.sqrt(holdingDays);
    }
}
