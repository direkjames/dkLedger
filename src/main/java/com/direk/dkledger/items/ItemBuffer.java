package com.direk.dkledger.items;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Item trades collected between database writes, with their daily totals per item, shop and side.
 * Each write adds the totals to the ones already saved. Thread-safe; plain Java so it can be tested.
 */
public final class ItemBuffer {

    /** One item's trades with one shop on one day, one side (bought or sold). */
    public record ItemDay(String day, String item, String source, ItemTrade.Side side, int trades, long quantity, double money) {
    }

    public record Drained(List<ItemTrade> trades, List<ItemDay> days) {
        public boolean isEmpty() {
            return trades.isEmpty() && days.isEmpty();
        }
    }

    private final ZoneId zone;
    private final List<ItemTrade> trades = new ArrayList<>();
    private final Map<String, double[]> days = new LinkedHashMap<>();

    public ItemBuffer(ZoneId zone) {
        this.zone = zone;
    }

    public String day(long time) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(time), zone).toString();
    }

    public synchronized void add(ItemTrade t) {
        if (t.quantity() <= 0 && t.money() == 0) return;
        trades.add(t);
        double[] d = days.computeIfAbsent(day(t.time()) + "\t" + t.item() + "\t" + t.side().name() + "\t" + t.source(), k -> new double[3]);
        d[0]++;
        d[1] += t.quantity();
        d[2] += t.money();
    }

    public synchronized int size() {
        return trades.size();
    }

    /** Takes everything collected so far and starts empty. */
    public synchronized Drained drain() {
        List<ItemTrade> t = List.copyOf(trades);
        List<ItemDay> d = new ArrayList<>();
        for (Map.Entry<String, double[]> e : days.entrySet()) {
            String[] k = e.getKey().split("\t", 4);
            double[] v = e.getValue();
            d.add(new ItemDay(k[0], k[1], k[3], ItemTrade.Side.valueOf(k[2]), (int) v[0], (long) v[1], v[2]));
        }
        trades.clear();
        days.clear();
        return new Drained(t, d);
    }
}
