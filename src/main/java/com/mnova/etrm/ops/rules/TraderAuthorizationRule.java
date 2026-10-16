package com.mnova.etrm.ops.rules;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.ops.OpsContext;
import com.mnova.etrm.ops.RuleResult;
import com.mnova.etrm.ops.TradeOpsRule;

/** A trader may only book into portfolios they are authorized for (segregation of books). */
public final class TraderAuthorizationRule implements TradeOpsRule {

    @Override
    public String name() {
        return "TraderAuthorization";
    }

    @Override
    public RuleResult evaluate(Deal d, TranStatus target, OpsContext ctx) {
        if (ctx.session().authorizedPortfolios(d.trader()).contains(d.portfolio())) {
            return RuleResult.pass(name());
        }
        return RuleResult.block(name(), "trader " + d.trader() + " not authorized for portfolio " + d.portfolio());
    }
}
