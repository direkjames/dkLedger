package com.direk.dkledger.players;

import com.direk.dkledger.DkLedger;
import com.direk.dkledger.ledger.LedgerStore;

import java.util.logging.Level;

/** Saves progress events (OneBlock phases, bank tiers, and whatever other plugins report through the API). */
public final class ProgressService {

    private final DkLedger plugin;
    private final LedgerStore store;
    private volatile boolean enabled = true;

    public ProgressService(DkLedger plugin, LedgerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void configure(boolean enabled) {
        this.enabled = enabled;
    }

    public void record(ProgressEvent e) {
        if (!enabled || e == null || e.player() == null || e.type().isEmpty()) return;
        store.addEvent(e).whenComplete((v, err) -> {
            if (err != null) plugin.getLogger().log(Level.WARNING, "Couldn't save a progress event", err);
        });
    }
}
