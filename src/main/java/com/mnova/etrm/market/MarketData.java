package com.mnova.etrm.market;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Market data snapshot for one valuation date: forward curves, realized fixings,
 * a flat discount rate, and historical daily price changes used for VaR.
 */
public final class MarketData {

    private final LocalDate valuationDate;
    private final Map<String, ForwardCurve> curves = new HashMap<>();
    private final Map<String, Map<YearMonth, Double>> fixings = new HashMap<>();
    private final Map<String, List<Double>> dailyPriceChanges = new HashMap<>();
    private double discountRate;

    public MarketData(LocalDate valuationDate, double discountRate) {
        this.valuationDate = Objects.requireNonNull(valuationDate);
        this.discountRate = discountRate;
    }

    public LocalDate valuationDate() {
        return valuationDate;
    }

    public double discountRate() {
        return discountRate;
    }

    public MarketData addCurve(ForwardCurve curve) {
        curves.put(curve.index(), curve);
        return this;
    }

    public MarketData addFixing(String index, YearMonth month, double price) {
        fixings.computeIfAbsent(index, k -> new HashMap<>()).put(month, price);
        return this;
    }

    public MarketData addHistory(String index, List<Double> changes) {
        dailyPriceChanges.put(index, new ArrayList<>(changes));
        return this;
    }

    public ForwardCurve curve(String index) {
        ForwardCurve c = curves.get(index);
        if (c == null) {
            throw new MissingMarketDataException("No forward curve loaded for index '" + index + "'");
        }
        return c;
    }

    public boolean hasCurve(String index) {
        return curves.containsKey(index);
    }

    public Set<String> indices() {
        return Collections.unmodifiableSet(curves.keySet());
    }

    /**
     * Price to use for a period: the realized fixing if the month has settled,
     * otherwise the forward curve price.
     */
    public double priceFor(String index, YearMonth month, boolean realized) {
        if (realized) {
            Map<YearMonth, Double> f = fixings.get(index);
            if (f == null || !f.containsKey(month)) {
                throw new MissingMarketDataException("Missing fixing for " + index + " " + month);
            }
            return f.get(month);
        }
        return curve(index).price(month);
    }

    public double discountFactor(LocalDate paymentDate) {
        long days = ChronoUnit.DAYS.between(valuationDate, paymentDate);
        if (days <= 0) {
            return 1.0;
        }
        return Math.exp(-discountRate * days / 365.0);
    }

    public List<Double> history(String index) {
        return dailyPriceChanges.getOrDefault(index, List.of());
    }

    /** Copy of this snapshot with one curve shifted in parallel; used for sensitivities and scenarios. */
    public MarketData withShiftedCurve(String index, double delta) {
        MarketData copy = copyTo(valuationDate);
        copy.curves.put(index, curve(index).shifted(delta));
        return copy;
    }

    /** Copy of this snapshot re-dated, used for theta / PnL-explain. */
    public MarketData copyTo(LocalDate newDate) {
        MarketData copy = new MarketData(newDate, discountRate);
        copy.curves.putAll(curves);
        fixings.forEach((k, v) -> copy.fixings.put(k, new HashMap<>(v)));
        copy.dailyPriceChanges.putAll(dailyPriceChanges);
        return copy;
    }

    public static final class MissingMarketDataException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MissingMarketDataException(String message) {
            super(message);
        }
    }
}
