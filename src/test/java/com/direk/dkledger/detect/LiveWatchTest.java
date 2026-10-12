package com.direk.dkledger.detect;

import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.ledger.Account;
import com.direk.dkledger.ledger.LedgerEntry;
import com.direk.dkledger.ledger.Sources;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveWatchTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Sources RULES = new Sources(List.of("Essentials /pay"), List.of("dkBank"));

    private static LiveWatch watch(Map<UUID, Set<String>> connections) {
        LiveWatch w = new LiveWatch(RULES, Thresholds.defaults(), ZoneId.of("UTC"), id -> connections.getOrDefault(id, Set.of()));
        w.knownSources(List.of("EconomyShopGUI", "AxAFKZone", "Essentials /pay"));
        return w;
    }

    private static LedgerEntry entry(long time, UUID who, double amount, String source, UUID other) {
        return new LedgerEntry(0, time, who, "Steve", Account.WALLET, amount, source, "", other, Double.NaN);
    }

    private static List<AlertDraft> run(LiveWatch w, LedgerEntry... entries) {
        List<AlertDraft> all = new ArrayList<>();
        for (LedgerEntry e : entries) all.addAll(w.check(e, "Steve"));
        return all;
    }

    @Test
    void earningsPerHourWarnOnceThenCritical() {
        LiveWatch w = watch(Map.of());
        List<AlertDraft> a = run(w, entry(0, STEVE, 6_000, "EconomyShopGUI", null), entry(1_000, STEVE, 6_000, "EconomyShopGUI", null),
                entry(2_000, STEVE, 6_000, "EconomyShopGUI", null), entry(3_000, STEVE, 9_000, "EconomyShopGUI", null));
        List<AlertDraft> b3 = a.stream().filter(d -> d.check().equals("B3")).toList();
        assertEquals(2, b3.size());
        assertEquals(Severity.WARNING, b3.get(0).severity());
        assertEquals(Severity.CRITICAL, b3.get(1).severity());
        // An hour later the window is empty again.
        assertTrue(run(w, entry(3_700_000 + 4_000, STEVE, 6_000, "EconomyShopGUI", null)).isEmpty());
    }

    @Test
    void buyingAndSellingInALoop() {
        LiveWatch w = watch(Map.of());
        List<AlertDraft> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            all.addAll(w.check(entry(i * 1000L, STEVE, -0.4, "EconomyShopGUI", null), "Steve"));
            all.addAll(w.check(entry(i * 1000L + 500, STEVE, 0.45, "EconomyShopGUI", null), "Steve"));
        }
        assertTrue(all.stream().anyMatch(d -> d.check().equals("B4") && d.severity() == Severity.WARNING));
    }

    @Test
    void newSourcesOnlyAfterWarmup() {
        LiveWatch w = watch(Map.of());
        assertTrue(run(w, entry(0, STEVE, 50, "SomeQuests", null)).isEmpty(), "first day: sources are learned silently");
        w.pastWarmup(true);
        List<AlertDraft> a = run(w, entry(1, STEVE, 50, "NewCrates", null), entry(2, STEVE, 50, "NewCrates", null));
        assertEquals(1, a.stream().filter(d -> d.check().equals("B5")).count());
    }

    @Test
    void staffMoneyAndBigOrAltTransfers() {
        LiveWatch w = watch(Map.of(STEVE, Set.of("ip1"), ALT, Set.of("ip1")));
        assertTrue(run(w, entry(0, STEVE, 15_000, Sources.staff("Admin"), null)).stream().anyMatch(d -> d.check().equals("B6")));
        List<AlertDraft> a = run(w, entry(10, STEVE, 30_000, "Essentials /pay", ALT));
        assertTrue(a.stream().anyMatch(d -> d.check().equals("B10") && d.severity() == Severity.WARNING));
        assertTrue(a.stream().anyMatch(d -> d.check().equals("B9") && d.severity() == Severity.CRITICAL), "30k from the same connection");
        assertTrue(a.stream().noneMatch(d -> d.check().equals("B3")), "transfers aren't earnings");
    }
}
