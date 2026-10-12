package com.direk.dkledger.detect;

import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.supply.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HourlyChecksTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 100 * DAY;

    private static boolean has(List<AlertDraft> list, String check, Severity s) {
        return list.stream().anyMatch(d -> d.check().equals(check) && d.severity() == s);
    }

    @Test
    void fastGrowthIsCriticalButNotDuringWarmup() {
        Snapshot week = new Snapshot(NOW - 7 * DAY, 100_000, 0, 20, 10, 0.3, 0.4);
        Snapshot now = new Snapshot(NOW, 220_000, 0, 20, 10, 0.3, 0.4); // about 12% a day
        List<AlertDraft> a = HourlyChecks.run(Thresholds.defaults(), now, week, 0, Map.of(), Map.of(), Map.of());
        assertTrue(has(a, "B1", Severity.CRITICAL));
        List<AlertDraft> warm = HourlyChecks.run(Thresholds.defaults(), now, week, NOW - DAY, Map.of(), Map.of(), Map.of());
        assertTrue(warm.stream().noneMatch(d -> d.check().equals("B1")), "installed yesterday: still warming up");
    }

    @Test
    void oneBigSourceAndFewSinks() {
        Snapshot now = new Snapshot(NOW, 100_000, 0, 5, 5, 0.3, 0.4);
        Map<String, Double> net = new LinkedHashMap<>();
        net.put("VirtualSpawner", 70_000.0);
        net.put("ODailyQuests", 30_000.0);
        net.put("EconomyShopGUI", -10_000.0);
        List<AlertDraft> a = HourlyChecks.run(Thresholds.defaults(), now, null, 0, net, Map.of(), Map.of());
        assertTrue(has(a, "B2", Severity.CRITICAL), "70% from one source");
        assertTrue(a.stream().anyMatch(d -> d.check().equals("B2") && d.subject().equals("sinks") && d.severity() == Severity.CRITICAL),
                "sinks removed only 10%");
    }

    @Test
    void playersFarAboveTheMedian() {
        Snapshot now = new Snapshot(NOW, 100_000, 0, 5, 5, 0.3, 0.4);
        Map<String, Double> earned = Map.of("A", 500.0, "B", 600.0, "C", 700.0, "D", 800.0, "Grinder", 12_000.0);
        List<AlertDraft> a = HourlyChecks.run(Thresholds.defaults(), now, null, 0, Map.of(), earned, Map.of());
        assertEquals(1, a.stream().filter(d -> d.check().equals("B3")).count());
        assertTrue(has(a, "B3", Severity.CRITICAL), "12,000 is 17× the median of 700");
    }

    @Test
    void concentrationAndPrices() {
        Snapshot now = new Snapshot(NOW, 100_000, 0, 30, 10, 0.8, 0.7);
        List<AlertDraft> a = HourlyChecks.run(Thresholds.defaults(), now, null, 0, Map.of(), Map.of(),
                Map.of("diamond", new double[]{60, 40}, "iron_ingot", new double[]{4.1, 4.0}));
        assertTrue(has(a, "B7", Severity.CRITICAL));
        assertTrue(has(a, "B8", Severity.WARNING), "diamond up 50%");
        assertTrue(a.stream().noneMatch(d -> d.subject().equals("iron_ingot")));
    }
}
