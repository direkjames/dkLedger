package com.direk.dkledger.items;

import java.util.List;

/**
 * A sell-all gives one total for many items: this splits it between them, by amount × price (or by amount if
 * the prices aren't known), so each item's trades still add up to what was really paid.
 */
public final class ItemSplit {

    /** One item in a multi-item trade; {@code unitPrice} is 0 when unknown. */
    public record Part(String item, int amount, double unitPrice) {
    }

    private ItemSplit() {
    }

    public static double[] split(List<Part> parts, double total) {
        double[] out = new double[parts.size()];
        if (parts.isEmpty()) return out;
        boolean priced = parts.stream().allMatch(p -> p.unitPrice() > 0);
        double weight = 0;
        for (Part p : parts) weight += priced ? p.amount() * p.unitPrice() : p.amount();
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            double w = priced ? p.amount() * p.unitPrice() : p.amount();
            out[i] = weight > 0 ? total * w / weight : total / parts.size();
        }
        return out;
    }
}
