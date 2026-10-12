package com.direk.dkledger.ledger;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TransferAndRollupTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void pairsTheTwoHalvesOfATransfer() {
        TransferPairer p = new TransferPairer(2_000, 0.25);
        p.sent("AxAuctions", A, 1000, 0);
        assertNull(p.received("ChestShop", B, 1000, 10), "a different plugin isn't the other half");
        assertEquals(A, p.received("AxAuctions", B, 950, 20), "5% auction tax");
        assertNull(p.received("AxAuctions", B, 950, 30), "each half pairs once");
        p.sent("Essentials /pay", A, 100, 0);
        assertNull(p.received("Essentials /pay", B, 100, 5_000), "too far apart");
    }

    @Test
    void dailyTotalsAndEarnings() {
        Sources rules = new Sources(List.of("Essentials /pay"), List.of("dkBank"));
        RollupBuffer r = new RollupBuffer(ZoneId.of("UTC"), rules);
        r.add(new LedgerEntry(0, 0, A, "A", Account.WALLET, 500, "ODailyQuests", "", null, Double.NaN));
        r.add(new LedgerEntry(0, 1, A, "A", Account.WALLET, -200, "EconomyShopGUI", "", null, Double.NaN));
        r.add(new LedgerEntry(0, 2, A, "A", Account.WALLET, 300, "Essentials /pay", "", B, Double.NaN));
        r.add(new LedgerEntry(0, 3, A, "A", Account.WALLET, -1000, "dkBank", "", null, Double.NaN));
        RollupBuffer.Drained d = r.drain();
        assertEquals(4, d.sources().size());
        RollupBuffer.PlayerDay day = d.players().getFirst();
        assertEquals(500, day.earned(), 1e-9);
        assertEquals(200, day.spent(), 1e-9, "transfers and bank deposits aren't spending");
        assertEquals("1970-01-01", day.day());
        assertEquals(0, r.drain().sources().size(), "drained");
    }
}
