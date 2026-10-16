package com.mnova.etrm.lifecycle;

import com.mnova.etrm.adapter.EndurSession;
import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.TranStatus;
import com.mnova.etrm.ops.OpsContext;
import com.mnova.etrm.ops.OpsServiceEngine;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Deal lifecycle: book, validate, amend, cancel, mature.
 *
 * <p>Every status change follows the same pattern Endur uses for trade processing:
 * check the transition is legal, run pre-process ops rules (which may veto), persist,
 * then run post-process actions - here, an event row in {@code USER_TRADE_EVENTS} that
 * downstream interfaces (confirmations, settlements, GL) consume.</p>
 */
public final class TradeLifecycleService {

    public static final String EVENTS_TABLE = "USER_TRADE_EVENTS";

    private final EndurSession session;
    private final OpsServiceEngine ops;
    private final OpsContext ctx;

    public TradeLifecycleService(EndurSession session, OpsServiceEngine ops, OpsContext ctx) {
        this.session = session;
        this.ops = ops;
        this.ctx = ctx;
    }

    /** Books a new deal. Tracking and tran numbers are assigned here, never by the caller. */
    public Result book(Deal draft) {
        long dealNum = session.nextDealTrackingNum();
        Deal deal = draft.toBuilder()
                .dealTrackingNum(dealNum)
                .tranNum(session.nextTranNum())
                .version(1)
                .status(TranStatus.PENDING)
                .build();
        return transition(deal, TranStatus.NEW, "BOOK", true);
    }

    public Result validate(long dealTrackingNum) {
        Deal deal = load(dealTrackingNum);
        return transition(deal, TranStatus.VALIDATED, "VALIDATE", false);
    }

    /**
     * Amends economics or static fields. Produces a new version with a new tran number;
     * the previous version is retained in history for audit.
     */
    public Result amend(long dealTrackingNum, UnaryOperator<Deal.Builder> changes, String reason) {
        Deal current = load(dealTrackingNum);
        if (!current.status().canMoveTo(TranStatus.AMENDED)) {
            return rejectedTransition(current, TranStatus.AMENDED);
        }
        Deal amended = changes.apply(current.toBuilder())
                .dealTrackingNum(dealTrackingNum)
                .tranNum(session.nextTranNum())
                .version(current.version() + 1)
                .status(current.status())
                .build();
        session.audit(dealTrackingNum, "AMEND requested: " + reason);
        return transition(amended, TranStatus.AMENDED, "AMEND", true);
    }

    public Result cancel(long dealTrackingNum, String reason) {
        Deal deal = load(dealTrackingNum);
        session.audit(dealTrackingNum, "CANCEL requested: " + reason);
        return transition(deal, TranStatus.CANCELLED, "CANCEL", false);
    }

    /** EOD job: moves validated deals whose delivery has fully ended to MATURED. */
    public List<Deal> matureExpired(LocalDate asOf) {
        List<Deal> matured = new ArrayList<>();
        for (Deal d : session.query(x -> x.status() == TranStatus.VALIDATED && x.endDate().isBefore(asOf))) {
            Result r = transition(d, TranStatus.MATURED, "MATURE", false);
            if (r.success()) {
                matured.add(r.deal());
            }
        }
        return matured;
    }

    private Result transition(Deal deal, TranStatus target, String action, boolean newVersion) {
        if (!deal.status().canMoveTo(target)) {
            return rejectedTransition(deal, target);
        }
        OpsServiceEngine.Outcome outcome = ops.run(deal, target, ctx);
        if (outcome.blocked()) {
            session.audit(deal.dealTrackingNum(), action + " -> " + target + " BLOCKED: " + outcome.summary());
            return new Result(false, deal, outcome, outcome.summary());
        }
        TranStatus from = deal.status();
        Deal toSave = newVersion ? deal.toBuilder().status(target).build() : deal;
        toSave.setStatus(target);
        session.save(toSave);
        session.audit(deal.dealTrackingNum(), action + " " + from + " -> " + target + " (tran " + toSave.tranNum()
                + ", v" + toSave.version() + ") " + outcome.summary());
        postProcess(toSave, action, from);
        return new Result(true, toSave, outcome, outcome.summary());
    }

    private void postProcess(Deal deal, String action, TranStatus from) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deal_tracking_num", deal.dealTrackingNum());
        row.put("tran_num", deal.tranNum());
        row.put("version", deal.version());
        row.put("action", action);
        row.put("from_status", from.name());
        row.put("to_status", deal.status().name());
        row.put("event_time", LocalDateTime.now().toString());
        row.put("processed", Boolean.FALSE);
        session.insertUserTableRow(EVENTS_TABLE, row);
    }

    private Result rejectedTransition(Deal deal, TranStatus target) {
        String msg = "illegal status transition " + deal.status() + " -> " + target;
        session.audit(deal.dealTrackingNum(), msg);
        return new Result(false, deal, new OpsServiceEngine.Outcome(List.of()), msg);
    }

    private Deal load(long dealTrackingNum) {
        return session.current(dealTrackingNum)
                .orElseThrow(() -> new IllegalArgumentException("Unknown deal " + dealTrackingNum));
    }

    public record Result(boolean success, Deal deal, OpsServiceEngine.Outcome outcome, String message) {
    }
}
