package com.direk.dkledger.players;

import java.util.Locale;
import java.util.UUID;

/**
 * Something a player reached or did that isn't money: a OneBlock phase, a quest, a bank tier, a rank.
 * Kept forever (they're few), so new players' progress can be compared over time.
 *
 * @param time   epoch millis
 * @param player who
 * @param name   their name, or ""
 * @param plugin which plugin reported it ("AOneBlock", "dkBank", ...)
 * @param type   what kind of thing, lower case ("oneblock-phase", "quest", "bank-tier")
 * @param key    which one ("plains", "daily_3", "gold")
 * @param value  a number that goes with it (block count, tier level...), or 0
 */
public record ProgressEvent(long time, UUID player, String name, String plugin, String type, String key, double value) {

    public ProgressEvent {
        name = cut(name, 16);
        plugin = cut(plugin, 32);
        type = cut(type == null ? "" : type.strip().toLowerCase(Locale.ROOT).replace(' ', '-'), 32);
        key = cut(key, 64);
        if (Double.isNaN(value) || Double.isInfinite(value)) value = 0;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
