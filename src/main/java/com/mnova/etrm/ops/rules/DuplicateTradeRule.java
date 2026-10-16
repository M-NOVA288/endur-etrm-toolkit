package com.mnova.etrm.ops.rules;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.ops.OpsContext;
import com.mnova.etrm.ops.RuleResult;
import com.mnova.etrm.ops.TradeOpsRule;

/**
 * Prevents the same external trade (exchange/broker/ETP reference) being booked twice,
 * a common production issue when an interface replays a feed after a failure.
 */
public final class DuplicateTradeRule implements TradeOpsRule {

    @Override
    public String name() {
        return "DuplicateTrade";
    }

    @Override
    public RuleResult evaluate(Deal d, TranStatus target, OpsContext ctx) {
        if (d.externalRef() == null || d.externalRef().isBlank()) {
            return RuleResult.pass(name());
        }
        return ctx.session().findByExternalRef(d.externalRef())
                .filter(existing -> existing.dealTrackingNum() != d.dealTrackingNum())
                .filter(existing -> existing.status() != TranStatus.CANCELLED)
                .map(existing -> RuleResult.block(name(), "external ref " + d.externalRef()
                        + " already booked as deal " + existing.dealTrackingNum()))
                .orElse(RuleResult.pass(name()));
    }
}
