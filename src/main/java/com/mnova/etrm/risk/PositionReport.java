package com.mnova.etrm.risk;

import com.mnova.etrm.valuation.PeriodValue;
import com.mnova.etrm.valuation.ValuationResult;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Open position by portfolio / index / delivery month, built from unrealized periods.
 * Equivalent to an Endur position report used by traders and middle office.
 */
public final class PositionReport {

    public record Key(String portfolio, String index, YearMonth month) {
        static final Comparator<Key> ORDER = Comparator.comparing(Key::portfolio)
                .thenComparing(Key::index).thenComparing(Key::month);
    }

    public record Position(double volume, double delta) {
        Position plus(double v, double d) {
            return new Position(volume + v, delta + d);
        }
    }

    private final Map<Key, Position> rows = new TreeMap<>(Key.ORDER);

    public static PositionReport build(List<ValuationResult> results) {
        PositionReport r = new PositionReport();
        for (ValuationResult vr : results) {
            if (!vr.deal().status().isLive()) {
                continue;
            }
            for (PeriodValue p : vr.periods()) {
                if (p.realized()) {
                    continue;
                }
                Key k = new Key(vr.deal().portfolio(), vr.deal().index(), p.month());
                r.rows.merge(k, new Position(p.signedVolume(), p.delta()), (a, b) -> a.plus(b.volume(), b.delta()));
            }
        }
        return r;
    }

    public Map<Key, Position> rows() {
        return java.util.Collections.unmodifiableMap(rows);
    }

    /** Net discounted delta per index, the input to VaR. */
    public Map<String, Double> deltaByIndex() {
        Map<String, Double> out = new TreeMap<>();
        rows.forEach((k, v) -> out.merge(k.index(), v.delta(), Double::sum));
        return out;
    }

    public double netVolume(String portfolio, String index) {
        return rows.entrySet().stream()
                .filter(e -> e.getKey().portfolio().equals(portfolio) && e.getKey().index().equals(index))
                .mapToDouble(e -> e.getValue().volume()).sum();
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-14s %-16s %-8s %16s %16s%n", "Portfolio", "Index", "Month", "Volume", "Delta"));
        rows.forEach((k, v) -> sb.append(String.format("%-14s %-16s %-8s %,16.0f %,16.0f%n",
                k.portfolio(), k.index(), k.month(), v.volume(), v.delta())));
        return sb.toString();
    }
}
