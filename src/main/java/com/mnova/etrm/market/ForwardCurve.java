package com.mnova.etrm.market;

import java.time.YearMonth;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Monthly forward curve for a price index (Endur index / curve).
 * Missing months are linearly interpolated between neighbouring pillars and
 * extrapolated flat beyond the first/last pillar.
 */
public final class ForwardCurve {

    private final String index;
    private final String unit;
    private final NavigableMap<YearMonth, Double> pillars = new TreeMap<>();

    public ForwardCurve(String index, String unit) {
        this.index = Objects.requireNonNull(index);
        this.unit = unit;
    }

    public ForwardCurve put(YearMonth month, double price) {
        pillars.put(month, price);
        return this;
    }

    public String index() {
        return index;
    }

    public String unit() {
        return unit;
    }

    public boolean isEmpty() {
        return pillars.isEmpty();
    }

    public Map<YearMonth, Double> pillars() {
        return java.util.Collections.unmodifiableNavigableMap(pillars);
    }

    public double price(YearMonth month) {
        if (pillars.isEmpty()) {
            throw new IllegalStateException("Curve " + index + " has no pillars");
        }
        Double exact = pillars.get(month);
        if (exact != null) {
            return exact;
        }
        Map.Entry<YearMonth, Double> lo = pillars.floorEntry(month);
        Map.Entry<YearMonth, Double> hi = pillars.ceilingEntry(month);
        if (lo == null) {
            return hi.getValue();
        }
        if (hi == null) {
            return lo.getValue();
        }
        long span = monthsBetween(lo.getKey(), hi.getKey());
        long offset = monthsBetween(lo.getKey(), month);
        double w = (double) offset / span;
        return lo.getValue() + w * (hi.getValue() - lo.getValue());
    }

    /** Returns a copy with every pillar shifted by {@code delta} (parallel shock for scenario analysis). */
    public ForwardCurve shifted(double delta) {
        ForwardCurve c = new ForwardCurve(index, unit);
        pillars.forEach((m, p) -> c.put(m, p + delta));
        return c;
    }

    private static long monthsBetween(YearMonth a, YearMonth b) {
        return (b.getYear() - a.getYear()) * 12L + (b.getMonthValue() - a.getMonthValue());
    }
}
