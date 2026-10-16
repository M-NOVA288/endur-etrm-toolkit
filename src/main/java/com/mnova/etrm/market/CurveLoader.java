package com.mnova.etrm.market;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads forward curves from a CSV extract (index,unit,month,price), the typical
 * format delivered by a market data vendor feed into Endur's curve import.
 * Blank lines and lines starting with '#' are ignored.
 */
public final class CurveLoader {

    private CurveLoader() {
    }

    public static Map<String, ForwardCurve> load(Reader source) throws IOException {
        Map<String, ForwardCurve> curves = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(source)) {
            String line;
            int lineNo = 0;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                lineNo++;
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                if (header) {
                    header = false;
                    if (t.toLowerCase().startsWith("index")) {
                        continue;
                    }
                }
                String[] f = t.split(",");
                if (f.length != 4) {
                    throw new IOException("Curve file line " + lineNo + ": expected 4 fields, got " + f.length);
                }
                String index = f[0].trim();
                ForwardCurve c = curves.computeIfAbsent(index, k -> new ForwardCurve(k, f[1].trim()));
                try {
                    c.put(YearMonth.parse(f[2].trim()), Double.parseDouble(f[3].trim()));
                } catch (RuntimeException e) {
                    throw new IOException("Curve file line " + lineNo + ": " + e.getMessage(), e);
                }
            }
        }
        return curves;
    }
}
