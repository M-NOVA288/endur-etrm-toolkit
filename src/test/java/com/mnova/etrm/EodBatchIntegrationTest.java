package com.mnova.etrm;

import com.mnova.etrm.app.EodBatchRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the full sample EOD batch and checks the key control outcomes appear. */
class EodBatchIntegrationTest {

    @Test
    void sampleEodRunsEndToEnd() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            EodBatchRunner.main(new String[0]);
        } finally {
            System.setOut(original);
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("EOD COMPLETE"), out);
        assertTrue(out.contains("BLOCK PriceTolerance"));
        assertTrue(out.contains("BLOCK Counterparty"));
        assertTrue(out.contains("BLOCK TraderAuthorization"));
        assertTrue(out.contains("BLOCK DuplicateTrade"));
        assertTrue(out.contains("invalid date"));
        assertTrue(out.contains("balanced=true"));
        assertTrue(out.contains("BREAK WTI_NYMEX"));
    }
}
