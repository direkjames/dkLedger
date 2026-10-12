package com.direk.dkledger.items;

import java.util.Locale;
import java.util.UUID;

/**
 * One trade of items for money with a shop or market (not between players).
 *
 * @param time     epoch millis
 * @param player   who traded
 * @param name     their name, or ""
 * @param source   the shop: "EconomyShopGUI", "Lodestock", or the plugin that reported it
 * @param item     the item key, lower case ("diamond", "cobblestone", "spawner_zombie")
 * @param side     BUY = the player bought from the shop, SELL = the player sold to it
 * @param quantity how many items
 * @param money    money paid (BUY) or received (SELL), always positive
 * @param detail   extra info (how they traded: "QUICK_SELL", "sell all"...)
 */
public record ItemTrade(long time, UUID player, String name, String source, String item, Side side, int quantity,
                        double money, String detail) {

    public enum Side { BUY, SELL }

    public ItemTrade {
        item = key(item);
        if (name == null) name = "";
        if (detail == null) detail = "";
        money = Math.abs(money);
        quantity = Math.max(0, quantity);
    }

    /** Money per item, or 0. */
    public double unitPrice() {
        return quantity == 0 ? 0 : money / quantity;
    }

    /** One spelling for the same item from every plugin: "minecraft:Diamond Block" -> "diamond_block". */
    public static String key(String item) {
        if (item == null) return "unknown";
        String k = item.strip().toLowerCase(Locale.ROOT);
        if (k.startsWith("minecraft:")) k = k.substring("minecraft:".length());
        k = k.replace(' ', '_');
        if (k.isEmpty()) return "unknown";
        return k.length() <= 48 ? k : k.substring(0, 48);
    }
}
