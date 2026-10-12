package com.direk.dkledger.ledger;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelsTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void scopesNestAndClose() {
        Labels labels = new Labels(5_000);
        assertEquals("", labels.reasonFor(A, 0), "no label");
        try (Labels.Scope outer = labels.open("quest: daily_3")) {
            assertEquals("quest: daily_3", labels.reasonFor(A, 0));
            try (Labels.Scope inner = labels.open("bonus")) {
                assertEquals("bonus", labels.reasonFor(B, 0), "the innermost wins, for any player");
            }
            assertEquals("quest: daily_3", labels.reasonFor(A, 0));
            outer.close();
            outer.close(); // twice is harmless
            assertEquals("", labels.reasonFor(A, 0));
        }
    }

    @Test
    void scopeIsPerThread() throws Exception {
        Labels labels = new Labels(5_000);
        try (Labels.Scope s = labels.open("afk zone")) {
            String[] seen = new String[1];
            Thread t = new Thread(() -> seen[0] = labels.reasonFor(A, 0));
            t.start();
            t.join();
            assertEquals("", seen[0], "another thread doesn't see it");
        }
    }

    @Test
    void expectationsAreUsedOnceAndExpire() {
        Labels labels = new Labels(5_000);
        labels.expect(A, "vote reward", 1_000);
        assertEquals("", labels.reasonFor(B, 1_500), "only for that player");
        assertEquals("vote reward", labels.reasonFor(A, 2_000));
        assertEquals("", labels.reasonFor(A, 2_100), "used up");
        labels.expect(A, "late", 1_000);
        assertEquals("", labels.reasonFor(A, 7_000), "expired");
        String longReason = "x".repeat(100);
        labels.expect(A, longReason, 0);
        assertEquals(Labels.MAX_LENGTH, labels.reasonFor(A, 0).length(), "cut to fit the column");
    }

    @Test
    void reasonsGetDailyTotals() {
        RollupBuffer r = new RollupBuffer(ZoneId.of("UTC"), new Sources(List.of(), List.of()));
        r.add(new LedgerEntry(0, 0, A, "A", Account.WALLET, 500, "ODailyQuests", "", null, Double.NaN, "quest: daily_3"));
        r.add(new LedgerEntry(0, 1, B, "B", Account.WALLET, 250, "ODailyQuests", "", null, Double.NaN, "quest: daily_3"));
        r.add(new LedgerEntry(0, 2, A, "A", Account.WALLET, 100, "ODailyQuests", "", null, Double.NaN));
        RollupBuffer.Drained d = r.drain();
        assertEquals(1, d.reasons().size(), "changes without a reason aren't in the reason totals");
        assertEquals(750, d.reasons().getFirst().gained(), 1e-9);
        assertEquals(2, d.reasons().getFirst().entries());
        assertTrue(r.drain().isEmpty());
    }
}
