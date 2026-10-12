package com.direk.dkledger.audit.read;

import com.direk.dkledger.audit.AuditContext;
import com.direk.dkledger.audit.model.PriceEntry;
import com.direk.dkledger.audit.yaml.Node;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Reads Lodestock's items.yml. Prices move with stock, so the base price is used: players buy at the base
 * price and (if lodestock-tax-on-sell) sell at the base price minus tax-percent.
 */
public final class LodestockReader {

    public static final String SOURCE = "Lodestock";

    private LodestockReader() {
    }

    /** @return the tax percent, or -1 if Lodestock isn't installed */
    public static double read(AuditContext ctx) {
        Path folder = ctx.find(ctx.settings.lodestockFolder);
        if (folder == null) {
            ctx.read("Lodestock", ctx.settings.lodestockFolder.stream().map(f -> f + "/items.yml").toList());
            return -1;
        }
        Map<String, Object> config = ctx.read("Lodestock config", folder.resolve("config.yml"));
        Map<String, Object> items = ctx.read("Lodestock items", folder.resolve("items.yml"));
        if (items == null) return -1;
        double tax = config == null ? 0 : Node.num(Node.child(config, "tax-percent"), 0);
        double sellFactor = ctx.settings.lodestockTaxOnSell ? 1 - tax / 100 : 1;
        for (Map.Entry<String, Object> e : items.entrySet()) {
            double base = Node.num(Node.child(e.getValue(), "base-price"), -1);
            if (base <= 0) continue;
            String material = e.getKey().toUpperCase(Locale.ROOT);
            double dailySell = Node.num(Node.child(e.getValue(), "daily-sell"), 0);
            ctx.prices.add(new PriceEntry(SOURCE, material, base, base * sellFactor,
                    "Lodestock/items.yml: " + e.getKey() + (dailySell > 0 ? " (daily sell limit " + (long) dailySell + ")" : "")));
        }
        return tax;
    }
}
