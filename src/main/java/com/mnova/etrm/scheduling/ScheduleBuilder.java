package com.mnova.etrm.scheduling;

import com.mnova.etrm.domain.BuySell;
import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Toolset;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds a day's gas schedule from the traded book: matches supply (physical buys at receipt
 * points) to market (physical sells at delivery points) on each pipeline, and creates the
 * nominations needed to move the gas.
 *
 * <p>Rules applied, as a scheduler would:</p>
 * <ul>
 *   <li>Only live {@code PHYS_GAS} deals flowing on the gas day are scheduled.</li>
 *   <li>Firm capacity is used before interruptible; cheaper contracts first within each class.</li>
 *   <li>Contract MDQ caps receipt volume; fuel is grossed up so the market receives its full volume.</li>
 *   <li>Volumes are whole MMBtu, as pipelines schedule in whole units.</li>
 *   <li>Anything that cannot be pathed is reported as unmatched supply/market with a reason.</li>
 * </ul>
 */
public final class ScheduleBuilder {

    private static final double MIN_VOLUME = 1.0;

    private final Map<String, Location> locations;
    private final List<TransportContract> contracts;

    public ScheduleBuilder(List<Location> locations, List<TransportContract> contracts) {
        this.locations = new LinkedHashMap<>();
        locations.forEach(l -> this.locations.put(l.id(), l));
        this.contracts = List.copyOf(contracts);
    }

    public ScheduleResult build(LocalDate gasDay, NominationCycle cycle, List<Deal> deals, List<DealLocation> dealLocations) {
        Map<Long, String> pointByDeal = new LinkedHashMap<>();
        dealLocations.forEach(dl -> pointByDeal.put(dl.dealTrackingNum(), dl.pointId()));

        List<String> warnings = new ArrayList<>();
        Map<String, Deque<Leg>> supplyByPipe = new TreeMap<>();
        Map<String, Deque<Leg>> marketByPipe = new TreeMap<>();

        for (Deal d : deals) {
            if (d.toolset() != Toolset.PHYS_GAS || !d.status().isLive()
                    || gasDay.isBefore(d.startDate()) || gasDay.isAfter(d.endDate())) {
                continue;
            }
            String pointId = pointByDeal.get(d.dealTrackingNum());
            Location loc = pointId == null ? null : locations.get(pointId);
            if (loc == null) {
                warnings.add("deal " + d.dealTrackingNum() + " has no valid delivery location; not scheduled");
                continue;
            }
            Location.Type expected = d.buySell() == BuySell.BUY ? Location.Type.RECEIPT : Location.Type.DELIVERY;
            if (loc.type() != expected) {
                warnings.add("deal " + d.dealTrackingNum() + " is a " + d.buySell() + " at " + loc.type()
                        + " point " + loc.id() + "; expected " + expected);
                continue;
            }
            Map<String, Deque<Leg>> target = d.buySell() == BuySell.BUY ? supplyByPipe : marketByPipe;
            target.computeIfAbsent(loc.pipeline(), k -> new ArrayDeque<>())
                    .add(new Leg(d.dealTrackingNum(), loc.id(), Math.floor(d.quantity())));
        }

        List<Nomination> noms = new ArrayList<>();
        Map<Long, Double> unmatchedSupply = new TreeMap<>();
        Map<Long, Double> unmatchedMarket = new TreeMap<>();

        TreeSet<String> pipelines = new TreeSet<>(supplyByPipe.keySet());
        pipelines.addAll(marketByPipe.keySet());
        for (String pipe : pipelines) {
            Deque<Leg> supply = supplyByPipe.getOrDefault(pipe, new ArrayDeque<>());
            Deque<Leg> market = marketByPipe.getOrDefault(pipe, new ArrayDeque<>());
            List<Capacity> caps = contracts.stream()
                    .filter(c -> c.pipeline().equals(pipe))
                    .sorted(Comparator.comparing(TransportContract::service).thenComparingDouble(TransportContract::rate))
                    .map(c -> new Capacity(c, Math.floor(c.mdq())))
                    .toList();
            int seq = 0;
            for (Capacity cap : caps) {
                while (!supply.isEmpty() && !market.isEmpty() && cap.remaining >= MIN_VOLUME) {
                    Leg s = supply.peek();
                    Leg m = market.peek();
                    double receipt = Math.floor(Math.min(Math.min(s.remaining, cap.remaining),
                            Math.ceil(cap.contract.requiredReceipt(m.remaining))));
                    double delivery = Math.min(m.remaining, Math.floor(cap.contract.delivered(receipt)));
                    if (receipt < MIN_VOLUME || delivery < MIN_VOLUME) {
                        // Remainders below one unit cannot be scheduled; drop whichever leg is exhausted.
                        if (s.remaining < MIN_VOLUME + 1) supply.poll();
                        else if (m.remaining < MIN_VOLUME + 1) market.poll();
                        else break;
                        continue;
                    }
                    seq++;
                    noms.add(new Nomination(pipe + "-" + gasDay + "-" + seq, gasDay, cycle, pipe, cap.contract.id(),
                            s.point, m.point, s.deal, m.deal, receipt, delivery, 0, Nomination.Status.DRAFT));
                    s.remaining -= receipt;
                    m.remaining -= delivery;
                    cap.remaining -= receipt;
                    if (s.remaining < MIN_VOLUME) supply.poll();
                    if (m.remaining < MIN_VOLUME) market.poll();
                }
            }
            if (!supply.isEmpty() && !market.isEmpty()) {
                warnings.add("capacity constrained on " + pipe + ": supply and market remain but no contract capacity left");
            }
            supply.forEach(l -> unmatchedSupply.merge(l.deal, l.remaining, Double::sum));
            market.forEach(l -> unmatchedMarket.merge(l.deal, l.remaining, Double::sum));
        }
        return new ScheduleResult(gasDay, noms, unmatchedSupply, unmatchedMarket, warnings);
    }

    public TransportContract contract(String id) {
        return contracts.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown contract " + id));
    }

    private static final class Leg {
        final long deal;
        final String point;
        double remaining;

        Leg(long deal, String point, double remaining) {
            this.deal = deal;
            this.point = point;
            this.remaining = remaining;
        }
    }

    private static final class Capacity {
        final TransportContract contract;
        double remaining;

        Capacity(TransportContract contract, double remaining) {
            this.contract = contract;
            this.remaining = remaining;
        }
    }

    /** The day's schedule. Unmatched volumes are what the scheduler must still resolve (buy/sell spot, find capacity). */
    public record ScheduleResult(LocalDate gasDay, List<Nomination> nominations, Map<Long, Double> unmatchedSupply,
                                 Map<Long, Double> unmatchedMarket, List<String> warnings) {
        public ScheduleResult {
            nominations = List.copyOf(nominations);
            unmatchedSupply = Map.copyOf(unmatchedSupply);
            unmatchedMarket = Map.copyOf(unmatchedMarket);
            warnings = List.copyOf(warnings);
        }

        public double totalReceipt() {
            return nominations.stream().mapToDouble(Nomination::receiptVolume).sum();
        }

        public double totalDelivery() {
            return nominations.stream().mapToDouble(Nomination::deliveryVolume).sum();
        }
    }
}
