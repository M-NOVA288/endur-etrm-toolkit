package com.mnova.etrm.domain;

import java.util.Objects;

/**
 * A business unit. Internal BUs own portfolios; external BUs are counterparties
 * carrying a credit limit and an authorization status (as in Endur party maintenance).
 */
public record Party(String id, String name, PartyType type, double creditLimit, boolean authorized) {

    public enum PartyType { INTERNAL_BU, EXTERNAL_BU }

    public Party {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (creditLimit < 0) {
            throw new IllegalArgumentException("creditLimit must be >= 0");
        }
    }

    public static Party internal(String id, String name) {
        return new Party(id, name, PartyType.INTERNAL_BU, 0, true);
    }

    public static Party external(String id, String name, double creditLimit) {
        return new Party(id, name, PartyType.EXTERNAL_BU, creditLimit, true);
    }

    public Party suspend() {
        return new Party(id, name, type, creditLimit, false);
    }

    public boolean isInternal() {
        return type == PartyType.INTERNAL_BU;
    }
}
