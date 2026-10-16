package com.mnova.etrm.scheduling;

import java.util.Objects;

/**
 * A transportation agreement with a pipeline.
 *
 * @param mdq      maximum daily quantity, measured at the receipt point (MMBtu/d)
 * @param fuelRate fraction of received gas the pipeline retains as fuel/L&amp;U (e.g. 0.015)
 * @param rate     variable transport charge per MMBtu received
 */
public record TransportContract(String id, String pipeline, ServiceType service, double mdq,
                                double fuelRate, double rate) {

    /** Firm capacity is scheduled ahead of interruptible and is not cut except in force majeure. */
    public enum ServiceType { FIRM, INTERRUPTIBLE }

    public TransportContract {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(service, "service");
        if (mdq < 0) {
            throw new IllegalArgumentException("mdq must be >= 0");
        }
        if (fuelRate < 0 || fuelRate >= 1) {
            throw new IllegalArgumentException("fuelRate must be in [0, 1)");
        }
    }

    /** Delivered volume for a given received volume, after fuel retention. */
    public double delivered(double received) {
        return received * (1 - fuelRate);
    }

    /** Receipt volume required to deliver a given volume. */
    public double requiredReceipt(double delivered) {
        return delivered / (1 - fuelRate);
    }
}
