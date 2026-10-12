package dev.direk.dkledger.audit.read;

import dev.direk.dkledger.audit.AuditContext;
import dev.direk.dkledger.audit.model.PriceEntry;
import dev.direk.dkledger.audit.yaml.Node;

import java.util.Locale;
import java.util.Map;

/**
 * Reads HeadDatabase's config.yml. Only the "vanilla:" heads of the custom categories are real
 * materials (zombie head, dragon head...), so only those go into the price book. The category prices
 * are a money sink (A11).
 */
public final class HeadDatabaseReader {

    public static final String SOURCE = "HeadDatabase";
    private static final Map<String, String> VANILLA = Map.of(
            "skeleton", "SKELETON_SKULL", "wither_skeleton", "WITHER_SKELETON_SKULL", "zombie", "ZOMBIE_HEAD",
            "creeper", "CREEPER_HEAD", "ender_dragon", "DRAGON_HEAD", "piglin", "PIGLIN_HEAD");

    private HeadDatabaseReader() {
    }

    /** @return the config, or null if HeadDatabase isn't installed */
    public static Map<String, Object> read(AuditContext ctx) {
        Map<String, Object> config = ctx.read("HeadDatabase", ctx.settings.headDatabaseConfig);
        if (config == null || !Node.bool(Node.child(config, "Economy-Enabled"), true)) return config;
        var disabled = Node.strings(Node.child(config, "disabled-categories"));
        for (String category : new String[]{"custom", "custom2", "custom3", "custom4", "custom5"}) {
            if (disabled.contains(category)) continue;
            for (Map.Entry<String, Object> head : Node.map(Node.child(config, category)).entrySet()) {
                String value = Node.str(head.getValue(), "");
                int semicolon = head.getKey().lastIndexOf(';');
                if (!value.startsWith("vanilla:") || semicolon < 0) continue;
                String material = VANILLA.get(value.substring("vanilla:".length()).toLowerCase(Locale.ROOT));
                double price = Node.num(head.getKey().substring(semicolon + 1), -1);
                if (material != null && price > 0) {
                    ctx.prices.add(new PriceEntry(SOURCE, material, price, -1, "HeadDatabase/config.yml: " + category));
                }
            }
        }
        return config;
    }
}
