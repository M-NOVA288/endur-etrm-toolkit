package com.mnova.etrm.scheduling;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Nomination submission, pipeline constraint allocation and confirmation.
 *
 * <p>Typical day: submit Timely noms; the pipeline posts a constraint at a point and allocates
 * capacity (interruptible cut first, then firm pro rata); confirmations come back; the scheduler
 * re-nominates replacement supply in a later cycle for any market shortfall.</p>
 */
public final class NominationService {

    public record SubmitResult(List<Nomination> accepted, List<String> rejections) {
    }

    /** Submits draft nominations for a cycle; anything past the cycle deadline is rejected. */
    public SubmitResult submit(List<Nomination> noms, NominationCycle cycle, LocalDateTime now) {
        List<Nomination> accepted = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        for (Nomination n : noms) {
            LocalDateTime deadline = cycle.deadline(n.gasDay());
            if (now.isAfter(deadline)) {
                NominationCycle next = NominationCycle.nextOpen(n.gasDay(), now);
                rejections.add(n.id() + ": " + cycle + " deadline " + deadline + " has passed"
                        + (next == null ? "; no cycles left for gas day " + n.gasDay() : "; next open cycle is " + next));
                continue;
            }
            accepted.add(n.withCycle(cycle).withStatus(Nomination.Status.SUBMITTED));
        }
        return new SubmitResult(accepted, rejections);
    }

    /**
     * Allocates constrained capacity at a receipt point, returning confirmed receipt volume per
     * nomination. Interruptible service is cut first; if firm alone exceeds capacity, firm is
     * cut pro rata. Unconstrained nominations are confirmed in full.
     */
    public Map<String, Double> allocateConstraint(List<Nomination> noms, String receiptPoint, double available,
                                                  Function<String, TransportContract> contractLookup) {
        Map<String, Double> confirmed = new TreeMap<>();
        List<Nomination> atPoint = new ArrayList<>();
        for (Nomination n : noms) {
            if (n.receiptPoint().equals(receiptPoint)) {
                atPoint.add(n);
            } else {
                confirmed.put(n.id(), n.receiptVolume());
            }
        }
        Map<TransportContract.ServiceType, List<Nomination>> byService = atPoint.stream()
                .collect(Collectors.groupingBy(n -> contractLookup.apply(n.contractId()).service()));
        double remaining = Math.max(0, available);
        for (TransportContract.ServiceType service : TransportContract.ServiceType.values()) {
            List<Nomination> group = byService.getOrDefault(service, List.of());
            double requested = group.stream().mapToDouble(Nomination::receiptVolume).sum();
            if (requested <= remaining) {
                group.forEach(n -> confirmed.put(n.id(), n.receiptVolume()));
                remaining -= requested;
            } else {
                double ratio = requested == 0 ? 0 : remaining / requested;
                // Pro rata in whole units; any rounding remainder goes to the largest nominations first.
                List<Nomination> sorted = new ArrayList<>(group);
                sorted.sort(Comparator.comparingDouble(Nomination::receiptVolume).reversed());
                double allocated = 0;
                for (Nomination n : sorted) {
                    double v = Math.floor(n.receiptVolume() * ratio);
                    confirmed.put(n.id(), v);
                    allocated += v;
                }
                double leftover = Math.floor(remaining - allocated);
                for (int i = 0; leftover >= 1 && i < sorted.size(); i++, leftover--) {
                    Nomination n = sorted.get(i);
                    confirmed.put(n.id(), confirmed.get(n.id()) + 1);
                }
                remaining = 0;
            }
        }
        return confirmed;
    }

    /** Applies pipeline confirmations. Missing confirmations are treated as zero (fully cut). */
    public List<Nomination> confirm(List<Nomination> submitted, Map<String, Double> confirmedReceipt) {
        List<Nomination> out = new ArrayList<>();
        for (Nomination n : submitted) {
            double c = Math.min(n.receiptVolume(), Math.max(0, confirmedReceipt.getOrDefault(n.id(), 0.0)));
            Nomination.Status s = c >= n.receiptVolume() - 0.5 ? Nomination.Status.SCHEDULED : Nomination.Status.CUT;
            out.add(n.withScheduled(c, s));
        }
        return out;
    }

    /** Delivery shortfall per market deal caused by cuts, after fuel; the scheduler's re-nomination worklist. */
    public Map<Long, Double> marketShortfall(List<Nomination> confirmed, Function<String, TransportContract> contractLookup) {
        Map<Long, Double> out = new TreeMap<>();
        for (Nomination n : confirmed) {
            if (n.cutVolume() > 0) {
                double scheduledDelivery = Math.floor(contractLookup.apply(n.contractId()).delivered(n.scheduledVolume()));
                out.merge(n.marketDeal(), n.deliveryVolume() - scheduledDelivery, Double::sum);
            }
        }
        return out;
    }

    /** Variable transport cost of the scheduled volumes. */
    public double transportCost(List<Nomination> confirmed, Function<String, TransportContract> contractLookup) {
        return confirmed.stream()
                .mapToDouble(n -> n.scheduledVolume() * contractLookup.apply(n.contractId()).rate())
                .sum();
    }
}
