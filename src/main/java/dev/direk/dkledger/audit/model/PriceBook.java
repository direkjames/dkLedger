package dev.direk.dkledger.audit.model;

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

    public boolean isEmpty() {
        return byMaterial.isEmpty();
    }
}
