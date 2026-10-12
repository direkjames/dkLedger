package com.direk.dkledger.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily totals built up between database writes: per source (money in and out) and per player
 * (earned and spent from earning sources). Each write adds them to the totals already saved.
 */
public final class RollupBuffer {

    /** Money one source moved on one day, in one account. */
    public record SourceDay(String day, String source, Account account, double gained, double lost, int entries) {
    }

    /** What one player earned and spent on one day. */
    public record PlayerDay(String day, UUID player, String name, double earned, double spent) {
    }

    private final ZoneId zone;
    private final Sources sources;
    private final Map<String, double[]> bySource = new LinkedHashMap<>();
    private final Map<String, double[]> byPlayer = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();

    public RollupBuffer(ZoneId zone, Sources sources) {
        this.zone = zone;
        this.sources = sources;
    }

    public String day(long time) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(time), zone).toString();
    }

    public synchronized void add(LedgerEntry e) {
        String day = day(e.time());
        double[] s = bySource.computeIfAbsent(day + "\t" + e.source() + "\t" + e.account().name(), k -> new double[3]);
        if (e.amount() >= 0) s[0] += e.amount();
        else s[1] += -e.amount();
        s[2]++;
        if (sources.isEarning(e.source())) {
            String key = day + "\t" + e.player();
            double[] p = byPlayer.computeIfAbsent(key, k -> new double[2]);
            if (e.amount() >= 0) p[0] += e.amount();
            else p[1] += -e.amount();
            if (!e.name().isEmpty()) names.put(key, e.name());
        }
    }

    /** Takes everything collected so far and starts empty. */
    public synchronized Drained drain() {
        List<SourceDay> s = new ArrayList<>();
        for (Map.Entry<String, double[]> e : bySource.entrySet()) {
            String[] k = e.getKey().split("\t");
            s.add(new SourceDay(k[0], k[1], Account.valueOf(k[2]), e.getValue()[0], e.getValue()[1], (int) e.getValue()[2]));
        }
        List<PlayerDay> p = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byPlayer.entrySet()) {
            String[] k = e.getKey().split("\t");
            p.add(new PlayerDay(k[0], UUID.fromString(k[1]), names.getOrDefault(e.getKey(), ""), e.getValue()[0], e.getValue()[1]));
        }
        bySource.clear();
        byPlayer.clear();
        names.clear();
        return new Drained(s, p);
    }

    public record Drained(List<SourceDay> sources, List<PlayerDay> players) {
        public boolean isEmpty() {
            return sources.isEmpty() && players.isEmpty();
        }
    }
}
