package com.mnova.etrm.ops.rules;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Party;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.ops.OpsContext;
import com.mnova.etrm.ops.RuleResult;
import com.mnova.etrm.ops.TradeOpsRule;

import java.util.Optional;

/** Internal BU must be internal; external BU must exist, be external and be authorized (KYC/credit approved). */
public final class CounterpartyRule implements TradeOpsRule {

    @Override
    public String name() {
        return "Counterparty";
    }

    @Override
    public RuleResult evaluate(Deal d, TranStatus target, OpsContext ctx) {
        Optional<Party> internal = ctx.session().party(d.internalBu());
        if (internal.isEmpty() || !internal.get().isInternal()) {
            return RuleResult.block(name(), "internal BU '" + d.internalBu() + "' is not a known internal business unit");
        }
        Optional<Party> external = ctx.session().party(d.externalBu());
        if (external.isEmpty()) {
            return RuleResult.block(name(), "unknown counterparty '" + d.externalBu() + "'");
        }
        if (external.get().isInternal()) {
            return RuleResult.warn(name(), "inter-desk trade with internal BU " + d.externalBu());
        }
        if (!external.get().authorized()) {
            return RuleResult.block(name(), "counterparty " + d.externalBu() + " is suspended / not authorized");
        }
        return RuleResult.pass(name());
    }
}
