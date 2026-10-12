package com.direk.dkledger.items;

import io.github.direkjames.lodestock.api.TradeType;
import io.github.direkjames.lodestock.api.event.LodestockTradeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Lodestock's market trades (a sell-all fires one event per item type). Only loaded when Lodestock is enabled. */
public final class LodestockTradeHook implements Listener {

    private final ItemService items;

    public LodestockTradeHook(ItemService items) {
        this.items = items;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrade(LodestockTradeEvent e) {
        ItemTrade.Side side = e.getType() == TradeType.BUY ? ItemTrade.Side.BUY : ItemTrade.Side.SELL;
        items.record(new ItemTrade(System.currentTimeMillis(), e.getPlayer().getUniqueId(), e.getPlayer().getName(), "Lodestock",
                e.getItemId(), side, e.getAmount(), e.getTotal(), "new price " + Math.round(e.getNewPrice() * 100) / 100.0
                + ", stock " + e.getNewStock()));
    }
}
