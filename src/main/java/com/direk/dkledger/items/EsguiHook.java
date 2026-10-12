package com.direk.dkledger.items;

import com.direk.dkledger.platform.Reflect;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * EconomyShopGUI's shop trades, from its PostTransactionEvent. Read by name (dkLedger doesn't compile against
 * EconomyShopGUI), so a renamed method just means that detail is missing.
 * <p>
 * Single trades give the item, amount and price. Sell-all and sell-GUI trades give every item and amount but
 * one total: it's split between the items by their sell price (or by amount if that's unknown).
 */
public final class EsguiHook implements Listener {

    public static final String EVENT = "me.gypopo.economyshopgui.api.events.PostTransactionEvent";

    private final ItemService items;

    private EsguiHook(ItemService items) {
        this.items = items;
    }

    /** Hooks into EconomyShopGUI (Premium or free). False if neither is running or the event isn't found. */
    public static boolean install(Plugin owner, ItemService items) {
        for (String name : List.of("EconomyShopGUI-Premium", "EconomyShopGUI")) {
            Plugin shop = owner.getServer().getPluginManager().getPlugin(name);
            if (shop == null || !shop.isEnabled()) continue;
            Class<? extends Event> type = Reflect.eventClass(shop.getClass().getClassLoader(), EVENT);
            if (type == null) continue;
            EsguiHook hook = new EsguiHook(items);
            Reflect.listen(owner, hook, type, hook::onTransaction);
            return true;
        }
        return false;
    }

    private void onTransaction(Event e) {
        String result = Reflect.text(Reflect.call(e, "getTransactionResult"));
        if (!result.isEmpty() && !result.startsWith("SUCCESS")) return;
        String how = Reflect.text(Reflect.call(e, "getTransactionType"));
        String upper = how.toUpperCase(Locale.ROOT);
        ItemTrade.Side side = upper.contains("SELL") ? ItemTrade.Side.SELL : upper.contains("BUY") ? ItemTrade.Side.BUY : null;
        if (side == null) return;
        if (!(Reflect.call(e, "getPlayer") instanceof Player player)) return;
        long now = System.currentTimeMillis();
        String detail = how.toLowerCase(Locale.ROOT).replace('_', ' ');

        if (Reflect.call(e, "getItems") instanceof Map<?, ?> many && !many.isEmpty()) {
            double total = total(Reflect.call(e, "getPrices"), Reflect.number(Reflect.call(e, "getPrice"), 0));
            List<ItemSplit.Part> parts = new ArrayList<>();
            for (Map.Entry<?, ?> entry : many.entrySet()) {
                int amount = (int) Reflect.number(entry.getValue(), 0);
                double unit = Reflect.number(Reflect.call(entry.getKey(), side == ItemTrade.Side.SELL ? "getSellPrice" : "getBuyPrice"), 0);
                parts.add(new ItemSplit.Part(item(entry.getKey(), null), amount, unit));
            }
            double[] money = ItemSplit.split(parts, total);
            for (int i = 0; i < parts.size(); i++) {
                ItemSplit.Part p = parts.get(i);
                items.record(new ItemTrade(now, player.getUniqueId(), player.getName(), "EconomyShopGUI", p.item(), side, p.amount(),
                        money[i], detail));
            }
            return;
        }
        Object shopItem = Reflect.call(e, "getShopItem");
        int amount = (int) Reflect.number(Reflect.call(e, "getAmount"), 0);
        double price = Reflect.number(Reflect.call(e, "getPrice"), 0);
        items.record(new ItemTrade(now, player.getUniqueId(), player.getName(), "EconomyShopGUI", item(shopItem, e), side, amount,
                price, detail));
    }

    /** The item's material, from the shop item (or the event's item stack). */
    private static String item(Object shopItem, Object event) {
        for (Object o : new Object[]{Reflect.call(shopItem, "getItemToGive", "getShopItem", "getItem"), Reflect.call(event, "getItemStack")}) {
            if (o instanceof ItemStack stack && stack.getType() != null) return stack.getType().name();
        }
        String path = Reflect.text(Reflect.call(shopItem, "getItemPath"));
        return path.isEmpty() ? "unknown" : path;
    }

    /** The money of a multi-item trade: the Vault total if the currencies are named, otherwise all of them. */
    static double total(Object prices, double fallback) {
        if (!(prices instanceof Map<?, ?> map) || map.isEmpty()) return fallback;
        double vault = 0;
        double all = 0;
        boolean named = false;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            double v = Reflect.number(e.getValue(), 0);
            all += v;
            String type = (Reflect.text(Reflect.call(e.getKey(), "getType")) + " " + e.getKey()).toUpperCase(Locale.ROOT);
            if (type.contains("VAULT")) {
                vault += v;
                named = true;
            }
        }
        return named ? vault : all;
    }
}
