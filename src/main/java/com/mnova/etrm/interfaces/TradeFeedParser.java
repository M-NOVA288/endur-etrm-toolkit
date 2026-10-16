package com.mnova.etrm.interfaces;

import com.mnova.etrm.domain.BuySell;
import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Toolset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Inbound trade interface: parses a CSV trade capture extract (exchange clearing,
 * broker or ETP feed) into draft deals for booking.
 *
 * <p>Columns are located by header name, so column order changes from the source system
 * do not break the interface. Bad rows are rejected individually with a reason, and the
 * rest of the file still loads - the behaviour production support expects from a feed.</p>
 */
public final class TradeFeedParser {

    static final String[] REQUIRED = {
            "ext_ref", "trade_date", "toolset", "ins_type", "buy_sell", "internal_bu", "external_bu",
            "portfolio", "trader", "index", "price", "quantity", "unit", "start_date", "end_date"};

    public record Rejection(int line, String extRef, String reason) {
    }

    public record ParseResult(List<Deal> drafts, List<Rejection> rejections) {
    }

    public ParseResult parse(Reader source) throws IOException {
        List<Deal> drafts = new ArrayList<>();
        List<Rejection> rejections = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(source)) {
            String headerLine = r.readLine();
            if (headerLine == null) {
                return new ParseResult(drafts, rejections);
            }
            Map<String, Integer> col = new HashMap<>();
            List<String> header = CsvSupport.split(headerLine);
            for (int i = 0; i < header.size(); i++) {
                col.put(header.get(i).toLowerCase(Locale.ROOT), i);
            }
            for (String req : REQUIRED) {
                if (!col.containsKey(req)) {
                    throw new IOException("Trade feed header missing column '" + req + "'");
                }
            }
            String line;
            int lineNo = 1;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                List<String> f = CsvSupport.split(line);
                String extRef = get(f, col, "ext_ref");
                try {
                    drafts.add(toDraft(f, col));
                } catch (RuntimeException e) {
                    rejections.add(new Rejection(lineNo, extRef, e.getMessage()));
                }
            }
        }
        return new ParseResult(drafts, rejections);
    }

    private Deal toDraft(List<String> f, Map<String, Integer> col) {
        String toolsetRaw = get(f, col, "toolset");
        Toolset toolset;
        try {
            toolset = Toolset.valueOf(toolsetRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported toolset '" + toolsetRaw + "'");
        }
        return Deal.builder()
                .externalRef(get(f, col, "ext_ref"))
                .tradeDate(date(f, col, "trade_date"))
                .toolset(toolset)
                .insType(get(f, col, "ins_type"))
                .buySell(BuySell.parse(get(f, col, "buy_sell")))
                .internalBu(get(f, col, "internal_bu"))
                .externalBu(get(f, col, "external_bu"))
                .portfolio(get(f, col, "portfolio"))
                .trader(get(f, col, "trader"))
                .index(get(f, col, "index"))
                .fixedPrice(number(f, col, "price"))
                .quantity(number(f, col, "quantity"))
                .unit(get(f, col, "unit"))
                .currency(col.containsKey("currency") ? get(f, col, "currency") : "USD")
                .startDate(date(f, col, "start_date"))
                .endDate(date(f, col, "end_date"))
                .build();
    }

    private static String get(List<String> f, Map<String, Integer> col, String name) {
        int i = col.get(name);
        if (i >= f.size()) {
            throw new IllegalArgumentException("row too short, missing '" + name + "'");
        }
        return f.get(i);
    }

    private static double number(List<String> f, Map<String, Integer> col, String name) {
        String v = get(f, col, name);
        try {
            return Double.parseDouble(v.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid number for " + name + ": '" + v + "'");
        }
    }

    private static LocalDate date(List<String> f, Map<String, Integer> col, String name) {
        String v = get(f, col, name);
        try {
            return LocalDate.parse(v);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid date for " + name + ": '" + v + "'");
        }
    }
}
