package com.mnova.etrm;

import com.mnova.etrm.market.CurveLoader;
import com.mnova.etrm.market.ForwardCurve;
import com.mnova.etrm.market.MarketData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketDataTest {

    @Test
    void interpolatesLinearlyAndExtrapolatesFlat() {
        ForwardCurve c = new ForwardCurve("HH", "USD/MMBtu")
                .put(YearMonth.of(2027, 1), 4.0)
                .put(YearMonth.of(2027, 5), 3.0);
        assertEquals(4.0, c.price(YearMonth.of(2027, 1)), 1e-12);
        assertEquals(3.75, c.price(YearMonth.of(2027, 2)), 1e-12);
        assertEquals(3.25, c.price(YearMonth.of(2027, 4)), 1e-12);
        assertEquals(4.0, c.price(YearMonth.of(2026, 6)), 1e-12);
        assertEquals(3.0, c.price(YearMonth.of(2030, 1)), 1e-12);
        assertEquals(3.5, c.shifted(0.5).price(YearMonth.of(2030, 1)), 1e-12);
    }

    @Test
    void loadsCurveCsvAndReportsBadLines() throws IOException {
        String csv = "index,unit,month,price\n# comment\nHH,USD/MMBtu,2027-01,4.1\nHH,USD/MMBtu,2027-02,3.9\nPWR,USD/MWh,2027-01,55\n";
        Map<String, ForwardCurve> curves = CurveLoader.load(new StringReader(csv));
        assertEquals(2, curves.size());
        assertEquals(3.9, curves.get("HH").price(YearMonth.of(2027, 2)), 1e-12);
        IOException e = assertThrows(IOException.class,
                () -> CurveLoader.load(new StringReader("HH,USD,2027-01\n")));
        assertEquals(true, e.getMessage().contains("line 1"));
    }

    @Test
    void discountFactorAndMissingData() {
        MarketData md = new MarketData(LocalDate.of(2026, 1, 1), 0.05);
        assertEquals(1.0, md.discountFactor(LocalDate.of(2025, 12, 1)), 1e-12);
        assertEquals(Math.exp(-0.05), md.discountFactor(LocalDate.of(2027, 1, 1)), 1e-12);
        assertThrows(MarketData.MissingMarketDataException.class, () -> md.curve("NOPE"));
        assertThrows(MarketData.MissingMarketDataException.class,
                () -> md.priceFor("HH", YearMonth.of(2025, 12), true));
    }
}
