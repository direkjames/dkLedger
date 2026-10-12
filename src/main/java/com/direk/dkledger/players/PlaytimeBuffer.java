package com.direk.dkledger.players;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minutes played per player per day, collected between database writes. Each write adds them to the minutes
 * already saved. Thread-safe; plain Java so it can be tested.
 */
public final class PlaytimeBuffer {

    /** One player's day: minutes active, minutes AFK, and how many times they joined. */
    public record PlayerDay(String day, UUID player, int activeMinutes, int afkMinutes, int sessions) {
    }

    private final Map<String, int[]> days = new LinkedHashMap<>();

    public synchronized void minute(String day, UUID player, boolean afk) {
        int[] d = get(day, player);
        if (afk) d[1]++;
        else d[0]++;
    }

    public synchronized void joined(String day, UUID player) {
        get(day, player)[2]++;
    }

    private int[] get(String day, UUID player) {
        return days.computeIfAbsent(day + "\t" + player, k -> new int[3]);
    }

    public synchronized List<PlayerDay> drain() {
        List<PlayerDay> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : days.entrySet()) {
            String[] k = e.getKey().split("\t");
            int[] v = e.getValue();
            out.add(new PlayerDay(k[0], UUID.fromString(k[1]), v[0], v[1], v[2]));
        }
        days.clear();
        return out;
    }
}
