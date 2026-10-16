package com.mnova.etrm.app;

import com.mnova.etrm.adapter.EndurSession;
import com.mnova.etrm.adapter.InMemoryEndurSession;
import com.mnova.etrm.domain.Party;
import com.mnova.etrm.market.CurveLoader;
import com.mnova.etrm.market.ForwardCurve;
import com.mnova.etrm.market.MarketData;
import com.mnova.etrm.scheduling.Location;
import com.mnova.etrm.scheduling.TransportContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Static data and market data for the demo / integration tests. All names and prices are
 * fictional and illustrative only.
 */
public final class SampleEnvironment {

    public static final LocalDate VALUATION_DATE = LocalDate.of(2026, 10, 15);

    private SampleEnvironment() {
    }

    public static EndurSession session() {
        EndurSession s = new InMemoryEndurSession();
        s.registerParty(Party.internal("HOU_GAS_DESK", "Houston Gas Trading Desk"));
        s.registerParty(Party.internal("HOU_POWER_DESK", "Houston Power Trading Desk"));
        s.registerParty(Party.external("GULFCOAST_ENERGY", "Gulfcoast Energy Marketing LLC", 5_000_000));
        s.registerParty(Party.external("PERMIAN_MIDSTREAM", "Permian Midstream Partners LP", 3_000_000));
        s.registerParty(Party.external("LONESTAR_POWER", "Lonestar Power Supply Co", 4_000_000));
        s.registerParty(Party.external("CME_CLEARING", "Futures Clearing Broker", 25_000_000));
        s.registerParty(Party.external("BAYOU_HOLDINGS", "Bayou Holdings Inc", 1_000_000).suspend());
        s.registerParty(Party.external("HOUSTON_CITY_GAS", "Houston City Gas Distribution", 3_000_000));
        s.registerParty(Party.external("BAYTOWN_CHEMICALS", "Baytown Chemicals Plant", 1_500_000));
        s.authorizeTrader("jlopez", "GAS_PHYS_TX");
        s.authorizeTrader("jlopez", "GAS_FIN_HEDGE");
        s.authorizeTrader("jlopez", "CRUDE_SPEC");
        s.authorizeTrader("mchen", "PWR_ERCOT");
        return s;
    }

    public static MarketData marketData() throws IOException {
        MarketData md = new MarketData(VALUATION_DATE, 0.045);
        try (Reader r = resource("/sample/curves.csv")) {
            for (ForwardCurve c : CurveLoader.load(r).values()) {
                md.addCurve(c);
            }
        }
        md.addFixing("HH_NYMEX", YearMonth.of(2026, 7), 3.15)
          .addFixing("HH_NYMEX", YearMonth.of(2026, 8), 2.95)
          .addFixing("HH_NYMEX", YearMonth.of(2026, 9), 2.85)
          .addFixing("ERCOT_N_PEAK", YearMonth.of(2026, 7), 78.40)
          .addFixing("ERCOT_N_PEAK", YearMonth.of(2026, 8), 96.10)
          .addFixing("ERCOT_N_PEAK", YearMonth.of(2026, 9), 57.30)
          .addFixing("WTI_NYMEX", YearMonth.of(2026, 9), 72.05);
        // Synthetic daily price changes (seeded, reproducible) standing in for a price history table.
        md.addHistory("HH_NYMEX", syntheticChanges(250, 0.12, 11));
        md.addHistory("ERCOT_N_PEAK", syntheticChanges(250, 3.5, 23));
        md.addHistory("WTI_NYMEX", syntheticChanges(250, 1.4, 37));
        return md;
    }

    /** Fictional pipeline with one receipt hub and two delivery points. */
    public static List<Location> pipelinePoints() {
        return List.of(
                new Location("GS-R-KATY", "GULF_STAR", Location.Type.RECEIPT),
                new Location("GS-D-HOUSTON-CG", "GULF_STAR", Location.Type.DELIVERY),
                new Location("GS-D-BAYTOWN", "GULF_STAR", Location.Type.DELIVERY));
    }

    public static List<TransportContract> transportContracts() {
        return List.of(
                new TransportContract("GS-FT-001", "GULF_STAR", TransportContract.ServiceType.FIRM, 9_000, 0.015, 0.12),
                new TransportContract("GS-IT-001", "GULF_STAR", TransportContract.ServiceType.INTERRUPTIBLE, 10_000, 0.015, 0.18));
    }

    /** Physical location of each sample trade, keyed by its external reference. */
    public static Map<String, String> pointByExternalRef() {
        return Map.of(
                "ICE-778101", "GS-R-KATY",
                "ICE-778120", "GS-D-HOUSTON-CG",
                "ICE-778121", "GS-D-BAYTOWN");
    }

    public static Reader resource(String path) throws IOException {
        InputStream in = SampleEnvironment.class.getResourceAsStream(path);
        if (in == null) {
            throw new IOException("Resource not found: " + path);
        }
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    static List<Double> syntheticChanges(int n, double vol, long seed) {
        Random rnd = new Random(seed);
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(rnd.nextGaussian() * vol);
        }
        return out;
    }
}
