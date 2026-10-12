package com.direk.dkledger.items;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTrackingTest {

    private static final UUID A = UUID.randomUUID();

    @Test
    void itemKeysMatchAcrossPlugins() {
        assertEquals("diamond_block", ItemTrade.key("minecraft:Diamond Block"));
        assertEquals("diamond_block", ItemTrade.key("DIAMOND_BLOCK"));
        assertEquals("unknown", ItemTrade.key("  "));
    }

    @Test
    void dailyTotalsPerItemShopAndSide() {
        ItemBuffer b = new ItemBuffer(ZoneId.of("UTC"));
        b.add(new ItemTrade(0, A, "A", "Lodestock", "diamond", ItemTrade.Side.SELL, 10, 500, ""));
        b.add(new ItemTrade(1, A, "A", "Lodestock", "DIAMOND", ItemTrade.Side.SELL, 5, 240, ""));
        b.add(new ItemTrade(2, A, "A", "Lodestock", "diamond", ItemTrade.Side.BUY, 1, -60, "")); // money is always positive
        b.add(new ItemTrade(3, A, "A", "EconomyShopGUI", "diamond", ItemTrade.Side.SELL, 2, 80, ""));
        ItemBuffer.Drained d = b.drain();
        assertEquals(4, d.trades().size());
        assertEquals(3, d.days().size());
        ItemBuffer.ItemDay sold = d.days().getFirst();
        assertEquals("diamond", sold.item());
        assertEquals(2, sold.trades());
        assertEquals(15, sold.quantity());
        assertEquals(740, sold.money(), 1e-9);
        assertEquals(60, d.days().get(1).money(), 1e-9);
        assertTrue(b.drain().isEmpty());
    }

    @Test
    void sellAllTotalIsSplitByValue() {
        double[] m = ItemSplit.split(List.of(new ItemSplit.Part("cobblestone", 64, 0.25), new ItemSplit.Part("diamond", 2, 40)), 96);
        assertEquals(16, m[0], 1e-9);
        assertEquals(80, m[1], 1e-9);
        double[] byAmount = ItemSplit.split(List.of(new ItemSplit.Part("a", 30, 0), new ItemSplit.Part("b", 10, 5)), 100);
        assertEquals(75, byAmount[0], 1e-9, "a price is missing: split by amount");
        assertEquals(25, byAmount[1], 1e-9);
        assertEquals(0, ItemSplit.split(List.of(), 10).length);
    }
}
