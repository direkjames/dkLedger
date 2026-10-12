package com.direk.dkledger.players;

import dev.direk.dkbank.api.event.BankTierChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Locale;

/** dkBank tier changes, recorded as progress events. Only loaded when dkBank is enabled. */
public final class BankTierHook implements Listener {

    private final ProgressService progress;

    public BankTierHook(ProgressService progress) {
        this.progress = progress;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTier(BankTierChangeEvent e) {
        if (e.getAccount() == null || e.getTier() == null) return;
        String name = Bukkit.getOfflinePlayer(e.getAccount()).getName();
        progress.record(new ProgressEvent(System.currentTimeMillis(), e.getAccount(), name, "dkBank",
                "bank-tier-" + e.getCause().name().toLowerCase(Locale.ROOT), e.getTier().id(), e.getTier().rank()));
    }
}
