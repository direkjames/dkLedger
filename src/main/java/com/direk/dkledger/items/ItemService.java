package com.direk.dkledger.items;

import com.direk.dkledger.DkLedger;
import com.direk.dkledger.ledger.LedgerStore;

import java.time.ZoneId;
import java.util.logging.Level;

/**
 * Item trades: what players buy and sell, how many, and for how much. Fed by the shop hooks
 * ({@link LodestockTradeHook}, {@link EsguiHook}) and by other plugins through the API. Saved in batches
 * with the ledger. Can be called from any thread.
 */
public final class ItemService {

    private final DkLedger plugin;
    private final LedgerStore store;
    private volatile ItemBuffer buffer = new ItemBuffer(ZoneId.systemDefault());
    private volatile boolean enabled = true;

    public ItemService(DkLedger plugin, LedgerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void configure(boolean enabled, ZoneId zone) {
        flush();
        this.enabled = enabled;
        this.buffer = new ItemBuffer(zone);
    }

    public boolean enabled() {
        return enabled;
    }

    public void record(ItemTrade trade) {
        if (!enabled || trade == null) return;
        try {
            buffer.add(trade);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't record an item trade", e);
        }
    }

    public void flush() {
        ItemBuffer.Drained d = buffer.drain();
        if (d.isEmpty()) return;
        store.saveTrades(d).whenComplete((v, err) -> {
            if (err != null) plugin.getLogger().log(Level.SEVERE, "Couldn't save " + d.trades().size() + " item trades", err);
        });
    }
}
