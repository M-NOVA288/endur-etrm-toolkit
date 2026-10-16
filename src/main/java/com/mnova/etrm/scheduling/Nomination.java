package com.mnova.etrm.scheduling;

import java.time.LocalDate;

/**
 * A nomination: a request to the pipeline to move gas on a contract from a receipt point
 * to a delivery point on a gas day. Links back to the supply and market deals it fulfils.
 *
 * @param receiptVolume   volume nominated at the receipt point
 * @param deliveryVolume  volume nominated at the delivery point (receipt less fuel)
 * @param scheduledVolume receipt volume the pipeline confirmed; less than nominated means a cut
 */
public record Nomination(
        String id,
        LocalDate gasDay,
        NominationCycle cycle,
        String pipeline,
        String contractId,
        String receiptPoint,
        String deliveryPoint,
        long supplyDeal,
        long marketDeal,
        double receiptVolume,
        double deliveryVolume,
        double scheduledVolume,
        Status status) {

    public enum Status { DRAFT, SUBMITTED, SCHEDULED, CUT, REJECTED }

    public Nomination withStatus(Status s) {
        return new Nomination(id, gasDay, cycle, pipeline, contractId, receiptPoint, deliveryPoint,
                supplyDeal, marketDeal, receiptVolume, deliveryVolume, scheduledVolume, s);
    }

    public Nomination withCycle(NominationCycle c) {
        return new Nomination(id, gasDay, c, pipeline, contractId, receiptPoint, deliveryPoint,
                supplyDeal, marketDeal, receiptVolume, deliveryVolume, scheduledVolume, status);
    }

    public Nomination withScheduled(double scheduled, Status s) {
        return new Nomination(id, gasDay, cycle, pipeline, contractId, receiptPoint, deliveryPoint,
                supplyDeal, marketDeal, receiptVolume, deliveryVolume, scheduled, s);
    }

    public double cutVolume() {
        return Math.max(0, receiptVolume - scheduledVolume);
    }
}
