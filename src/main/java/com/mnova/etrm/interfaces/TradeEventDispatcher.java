package com.mnova.etrm.interfaces;

import com.mnova.etrm.adapter.EndurSession;
import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.lifecycle.TradeLifecycleService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consumes unprocessed rows in {@code USER_TRADE_EVENTS} and emits outbound confirmations.
 * Each row is marked processed only after its document is produced, so a failure mid-run
 * can be safely re-run (at-least-once, with the confirmation keyed by tran_num).
 */
public final class TradeEventDispatcher {

    private static final Set<String> CONFIRMABLE = Set.of("BOOK", "AMEND", "CANCEL");

    private final EndurSession session;
    private final ConfirmationWriter writer;

    public TradeEventDispatcher(EndurSession session, ConfirmationWriter writer) {
        this.session = session;
        this.writer = writer;
    }

    public record Dispatched(long tranNum, String action, String document) {
    }

    public List<Dispatched> dispatchPending() {
        List<Dispatched> out = new ArrayList<>();
        for (Map<String, Object> row : session.userTable(TradeLifecycleService.EVENTS_TABLE)) {
            if (Boolean.TRUE.equals(row.get("processed"))) {
                continue;
            }
            long dealNum = ((Number) row.get("deal_tracking_num")).longValue();
            long tranNum = ((Number) row.get("tran_num")).longValue();
            String action = (String) row.get("action");
            if (CONFIRMABLE.contains(action)) {
                Deal version = session.history(dealNum).stream()
                        .filter(d -> d.tranNum() == tranNum)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("event references unknown tran " + tranNum));
                out.add(new Dispatched(tranNum, action, writer.toXml(version, action)));
            }
            session.updateUserTableRows(TradeLifecycleService.EVENTS_TABLE,
                    r -> tranNum == ((Number) r.get("tran_num")).longValue() && action.equals(r.get("action")),
                    Map.of("processed", Boolean.TRUE));
        }
        return out;
    }
}
