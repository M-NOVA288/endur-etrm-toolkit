package com.mnova.etrm.scheduling;

import java.util.Objects;

/** A pipeline point where gas is received from supply or delivered to market. */
public record Location(String id, String pipeline, Type type) {

    public enum Type { RECEIPT, DELIVERY }

    public Location {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(type, "type");
    }
}
