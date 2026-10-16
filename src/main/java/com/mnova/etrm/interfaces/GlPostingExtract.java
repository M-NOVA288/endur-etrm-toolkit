package com.mnova.etrm.interfaces;

import com.mnova.etrm.settlement.Cashflow;
import com.mnova.etrm.valuation.ValuationResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds balanced double-entry journal lines for the general ledger:
 * realized settlements (AR/AP against revenue/cost) and unrealized MtM
 * (derivative asset/liability against unrealized P&amp;L).
 */
public final class GlPostingExtract {

    public record JournalLine(LocalDate postingDate, long dealTrackingNum, String account,
                              double debit, double credit, String description) {
    }

    public List<JournalLine> build(LocalDate postingDate, List<Cashflow> cashflows, List<ValuationResult> mtm) {
        List<JournalLine> out = new ArrayList<>();
        for (Cashflow cf : cashflows) {
            double a = Math.abs(cf.amount());
            String desc = cf.type() + " " + cf.counterparty() + " " + cf.deliveryMonth();
            if (cf.amount() >= 0) {
                out.add(new JournalLine(postingDate, cf.dealTrackingNum(), "1200-AR", a, 0, desc));
                out.add(new JournalLine(postingDate, cf.dealTrackingNum(), "4000-REVENUE", 0, a, desc));
            } else {
                out.add(new JournalLine(postingDate, cf.dealTrackingNum(), "5000-COST", a, 0, desc));
                out.add(new JournalLine(postingDate, cf.dealTrackingNum(), "2100-AP", 0, a, desc));
            }
        }
        for (ValuationResult v : mtm) {
            double u = v.unrealizedMtm();
            if (Math.abs(u) < 0.005) {
                continue;
            }
            long id = v.deal().dealTrackingNum();
            String desc = "Unrealized MtM " + v.deal().portfolio();
            if (u > 0) {
                out.add(new JournalLine(postingDate, id, "1500-DERIV-ASSET", u, 0, desc));
                out.add(new JournalLine(postingDate, id, "4900-UNREALIZED-PNL", 0, u, desc));
            } else {
                out.add(new JournalLine(postingDate, id, "4900-UNREALIZED-PNL", -u, 0, desc));
                out.add(new JournalLine(postingDate, id, "2500-DERIV-LIAB", 0, -u, desc));
            }
        }
        return out;
    }

    public static boolean isBalanced(List<JournalLine> lines) {
        double dr = lines.stream().mapToDouble(JournalLine::debit).sum();
        double cr = lines.stream().mapToDouble(JournalLine::credit).sum();
        return Math.abs(dr - cr) < 0.01;
    }

    public String toCsv(List<JournalLine> lines) {
        StringBuilder sb = new StringBuilder("posting_date,deal_tracking_num,account,debit,credit,description\n");
        for (JournalLine l : lines) {
            sb.append(l.postingDate()).append(',').append(l.dealTrackingNum()).append(',').append(l.account())
                    .append(',').append(String.format("%.2f", l.debit())).append(',')
                    .append(String.format("%.2f", l.credit())).append(',')
                    .append(CsvSupport.escape(l.description())).append('\n');
        }
        return sb.toString();
    }
}
