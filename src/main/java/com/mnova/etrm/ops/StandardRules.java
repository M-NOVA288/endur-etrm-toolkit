package com.mnova.etrm.ops;

import com.mnova.etrm.ops.rules.CounterpartyRule;
import com.mnova.etrm.ops.rules.CreditLimitRule;
import com.mnova.etrm.ops.rules.DuplicateTradeRule;
import com.mnova.etrm.ops.rules.MandatoryFieldsRule;
import com.mnova.etrm.ops.rules.PriceToleranceRule;
import com.mnova.etrm.ops.rules.TraderAuthorizationRule;

import java.util.List;

/** The default rule chain, ordered cheapest/most fundamental first. */
public final class StandardRules {

    private StandardRules() {
    }

    public static OpsServiceEngine defaultEngine() {
        return new OpsServiceEngine(List.of(
                new MandatoryFieldsRule(),
                new DuplicateTradeRule(),
                new CounterpartyRule(),
                new TraderAuthorizationRule(),
                new PriceToleranceRule(0.15),
                new CreditLimitRule(0.80)));
    }
}
