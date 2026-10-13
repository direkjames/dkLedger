package com.direk.dkledger.summary;

import com.direk.dkledger.ledger.Sources;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryTest {

    private static Summary summary(double now, double then, int alerts, List<String> sources, int players, List<String> frozen) {
        return new Summary(0, now, then, 0, 0, alerts, 0, null, sources, players, frozen);
    }

    @Test
    void onlySentWhenSomethingDeservesALook() {
        assertFalse(summary(102_000, 100_000, 0, List.of(), 0, List.of()).worthSending(0.05), "+2% is quiet");
        assertFalse(summary(102_000, 100_000, 0, List.of(), 5, List.of()).worthSending(0.05), "new players alone are quiet");
        assertTrue(summary(106_000, 100_000, 0, List.of(), 0, List.of()).worthSending(0.05), "+6%");
        assertTrue(summary(94_000, 100_000, 0, List.of(), 0, List.of()).worthSending(0.05), "-6% too");
        assertTrue(summary(100_000, 100_000, 1, List.of(), 0, List.of()).worthSending(0.05), "an alert");
        assertTrue(summary(100_000, 100_000, 0, List.of("PinataParty"), 0, List.of()).worthSending(0.05), "a new source");
        assertTrue(summary(100_000, 100_000, 0, List.of(), 0, List.of("Steve")).worthSending(0.05), "a freeze");
        assertFalse(summary(Double.NaN, 100_000, 0, List.of(), 0, List.of()).worthSending(0.05), "no count yet");
        assertEquals(0.06, summary(106_000, 100_000, 0, List.of(), 0, List.of()).change(), 1e-9);
    }

    @Test
    void moneyFlowCountsFeesButNotTransfers() {
        Sources rules = new Sources(List.of("Essentials /pay"), List.of("dkBank"));
        Map<String, double[]> by = new LinkedHashMap<>();
        by.put("ODailyQuests", new double[]{5_000, 0});
        by.put("Lodestock", new double[]{1_000, 3_000});
        by.put("Essentials /pay", new double[]{2_000, 2_000});
        by.put("dkBank", new double[]{10_000, 10_050});  // the withdraw fee
        by.put("staff:direk", new double[]{500, 0});
        double[] f = Summary.flow(by, rules);
        assertEquals(5_500, f[0], 1e-9);
        assertEquals(2_050, f[1], 1e-9);
    }
}
