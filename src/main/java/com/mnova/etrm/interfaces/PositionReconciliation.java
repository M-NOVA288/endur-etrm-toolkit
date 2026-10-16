package com.mnova.etrm.interfaces;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Toolset;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reconciles internal futures positions against the clearing broker's statement.
 * Produces breaks (index/month, internal vs external, difference) for operations to investigate;
 * typical root causes are missed fills, late amendments or a feed replay.
 */
public final class PositionReconciliation {

    public record Key(String index, YearMonth month) implements Comparable<Key> {
        @Override
        public int compareTo(Key o) {
            int c = index.compareTo(o.index);
            return c != 0 ? c : month.compareTo(o.month);
        }
    }

    public record Break(Key key, double internal, double external) {
        public double difference() {
            return internal - external;
        }
    }

    private final double tolerance;

    public PositionReconciliation(double tolerance) {
        this.tolerance = tolerance;
    }

    public static Map<Key, Double> internalFuturesPositions(List<Deal> deals) {
        Map<Key, Double> out = new TreeMap<>();
        for (Deal d : deals) {
            if (d.toolset() != Toolset.COM_FUT || !d.status().isLive()) {
                continue;
            }
            d.periods().forEach(p -> out.merge(new Key(d.index(), p.month()), d.buySell().sign() * p.volume(), Double::sum));
        }
        return out;
    }

    public List<Break> reconcile(Map<Key, Double> internal, Map<Key, Double> broker) {
        TreeMap<Key, double[]> all = new TreeMap<>();
        internal.forEach((k, v) -> all.computeIfAbsent(k, x -> new double[2])[0] = v);
        broker.forEach((k, v) -> all.computeIfAbsent(k, x -> new double[2])[1] = v);
        List<Break> breaks = new ArrayList<>();
        all.forEach((k, v) -> {
            if (Math.abs(v[0] - v[1]) > tolerance) {
                breaks.add(new Break(k, v[0], v[1]));
            }
        });
        return breaks;
    }
}
