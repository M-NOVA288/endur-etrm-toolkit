package com.mnova.etrm.ops.rules;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.ops.OpsContext;
import com.mnova.etrm.ops.RuleResult;
import com.mnova.etrm.ops.TradeOpsRule;

import java.util.ArrayList;
import java.util.List;

/** Blocks deals missing fields required downstream (risk, settlement, confirmations). */
public final class MandatoryFieldsRule implements TradeOpsRule {

    @Override
    public String name() {
        return "MandatoryFields";
    }

    @Override
    public RuleResult evaluate(Deal d, TranStatus target, OpsContext ctx) {
        List<String> missing = new ArrayList<>();
        if (blank(d.index())) missing.add("index");
        if (blank(d.portfolio())) missing.add("portfolio");
        if (blank(d.internalBu())) missing.add("internalBu");
        if (blank(d.externalBu())) missing.add("externalBu");
        if (blank(d.trader())) missing.add("trader");
        if (blank(d.unit())) missing.add("unit");
        if (d.tradeDate() == null) missing.add("tradeDate");
        if (d.quantity() <= 0) missing.add("quantity>0");
        if (d.tradeDate() != null && d.tradeDate().isAfter(d.endDate())) missing.add("tradeDate<=endDate");
        return missing.isEmpty() ? RuleResult.pass(name())
                : RuleResult.block(name(), "missing/invalid: " + String.join(", ", missing));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
