package com.direk.dkledger.audit.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Every buy and sell price from every plugin, by material. */
public final class PriceBook {

    private final Map<String, List<PriceEntry>> byMaterial = new TreeMap<>();

    public void add(PriceEntry entry) {
        byMaterial.computeIfAbsent(entry.material(), k -> new ArrayList<>()).add(entry);
    }

    public List<PriceEntry> entries(String material) {
        return byMaterial.getOrDefault(material, List.of());
    }

    public Collection<String> materials() {
        return byMaterial.keySet();
    }

    public List<PriceEntry> all() {
        List<PriceEntry> all = new ArrayList<>();
        byMaterial.values().forEach(all::addAll);
        return all;
    }

    /** The cheapest place to buy this item, if anywhere sells it. */
    public Optional<PriceEntry> cheapestBuy(String material) {
        PriceEntry best = null;
        for (PriceEntry e : entries(material)) {
            if (e.buyable() && (best == null || e.buy() < best.buy())) best = e;
        }
        return Optional.ofNullable(best);
    }

    /** The best place to sell this item, if anywhere buys it. */
    public Optional<PriceEntry> bestSell(String material) {
        PriceEntry best = null;
        for (PriceEntry e : entries(material)) {
            if (e.sellable() && (best == null || e.sell() > best.sell())) best = e;
        }
        return Optional.ofNullable(best);
    }

    /** The best price a player gets for selling one, or 0. */
    public double sellValue(String material) {
        return bestSell(material).map(PriceEntry::sell).orElse(0.0);
    }

    /**
     * Changes one plugin's price for an item (for "what if"). Adds it if that plugin doesn't list the item.
     *
     * @param buy  new buy price, or NaN to keep it
     * @param sell new sell price, or NaN to keep it
     * @return how many entries changed (0 means a new one was added)
     */
    public int change(String material, String source, double buy, double sell) {
        List<PriceEntry> list = byMaterial.computeIfAbsent(material, k -> new ArrayList<>());
        int changed = 0;
        for (int i = 0; i < list.size(); i++) {
            PriceEntry e = list.get(i);
            if (!e.source().equalsIgnoreCase(source)) continue;
            list.set(i, new PriceEntry(e.source(), e.material(), Double.isNaN(buy) ? e.buy() : buy,
                    Double.isNaN(sell) ? e.sell() : sell, e.where()));
            changed++;
        }
        if (changed == 0) {
            list.add(new PriceEntry(source, material, Double.isNaN(buy) ? -1 : buy, Double.isNaN(sell) ? -1 : sell, "what if"));
        }
        return changed;
    }

    /** Replaces matching entries (for "what if" on spawner drops). */
    public void replace(java.util.function.Predicate<PriceEntry> match, java.util.function.UnaryOperator<PriceEntry> change) {
        for (List<PriceEntry> list : byMaterial.values()) {
            list.replaceAll(e -> match.test(e) ? change.apply(e) : e);
        }
    }

    public boolean isEmpty() {
        return byMaterial.isEmpty();
    }
}
