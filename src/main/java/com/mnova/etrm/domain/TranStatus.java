package com.mnova.etrm.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Transaction status lifecycle, modeled on Endur tran statuses.
 * Allowed transitions are enforced centrally so ops-service rules and
 * interfaces cannot push a deal into an invalid state.
 */
public enum TranStatus {
    PENDING,
    NEW,
    VALIDATED,
    AMENDED,
    MATURED,
    CLOSEOUT,
    CANCELLED;

    public Set<TranStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(NEW, CANCELLED);
            case NEW -> EnumSet.of(VALIDATED, AMENDED, CANCELLED);
            case VALIDATED -> EnumSet.of(AMENDED, MATURED, CLOSEOUT, CANCELLED);
            case AMENDED -> EnumSet.of(VALIDATED, AMENDED, CANCELLED);
            case MATURED, CLOSEOUT, CANCELLED -> EnumSet.noneOf(TranStatus.class);
        };
    }

    public boolean canMoveTo(TranStatus target) {
        return allowedNext().contains(target);
    }

    /** Statuses that count toward live position and risk. */
    public boolean isLive() {
        return this == NEW || this == VALIDATED || this == AMENDED;
    }
}
