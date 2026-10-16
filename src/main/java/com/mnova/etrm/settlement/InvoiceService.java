package com.mnova.etrm.settlement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups cashflows into invoices per counterparty / currency / payment date and applies
 * payment netting where the counterparty has a netting agreement in place.
 */
public final class InvoiceService {

    public record Invoice(String counterparty, String currency, LocalDate paymentDate,
                          List<Cashflow> lines, double netAmount) {
        public String direction() {
            return netAmount >= 0 ? "RECEIVE" : "PAY";
        }
    }

    private final Set<String> nettingCounterparties;

    public InvoiceService(Set<String> nettingCounterparties) {
        this.nettingCounterparties = Set.copyOf(nettingCounterparties);
    }

    public List<Invoice> build(List<Cashflow> cashflows) {
        Map<String, List<Cashflow>> groups = new LinkedHashMap<>();
        for (Cashflow cf : cashflows) {
            boolean net = nettingCounterparties.contains(cf.counterparty());
            // Without netting, payables and receivables go on separate documents.
            String side = net ? "NET" : (cf.amount() >= 0 ? "AR" : "AP");
            String key = cf.counterparty() + "|" + cf.currency() + "|" + cf.paymentDate() + "|" + side;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(cf);
        }
        List<Invoice> out = new ArrayList<>();
        groups.values().forEach(lines -> {
            Cashflow first = lines.get(0);
            double total = lines.stream().mapToDouble(Cashflow::amount).sum();
            out.add(new Invoice(first.counterparty(), first.currency(), first.paymentDate(), List.copyOf(lines), total));
        });
        out.sort(Comparator.comparing(Invoice::paymentDate).thenComparing(Invoice::counterparty));
        return out;
    }
}
