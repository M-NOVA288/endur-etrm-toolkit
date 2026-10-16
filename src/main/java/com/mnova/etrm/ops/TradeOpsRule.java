package com.mnova.etrm.ops;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.TranStatus;

/**
 * A pre-process trade rule, the toolkit's equivalent of an Endur Operation Services
 * (ops service) pre-process plugin: it runs before a status change is committed and can
 * veto it. Rules are small, single-purpose and independently testable.
 */
public interface TradeOpsRule {

    String name();

    /** Whether this rule should run for a move to {@code target}. Cancellations skip booking checks by default. */
    default boolean appliesTo(TranStatus target) {
        return target != TranStatus.CANCELLED;
    }

    RuleResult evaluate(Deal deal, TranStatus target, OpsContext ctx);
}
