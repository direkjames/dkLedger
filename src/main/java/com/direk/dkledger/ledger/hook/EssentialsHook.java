package com.direk.dkledger.ledger.hook;

import com.direk.dkledger.ledger.LedgerService;
import com.direk.dkledger.ledger.Sources;
import net.ess3.api.events.UserBalanceUpdateEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.math.BigDecimal;

/**
 * EssentialsX tells us about every wallet change it makes. Changes made through dkLedger's Vault proxy are
 * already recorded, so only the rest are recorded here: /eco from staff or from other plugins' reward
 * commands, /pay, /sell, and plugins that use Essentials directly.
 * <p>
 * Only loaded when EssentialsX is enabled.
 */
public final class EssentialsHook implements Listener {

    private final LedgerService ledger;

    public EssentialsHook(LedgerService ledger) {
        this.ledger = ledger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBalance(UserBalanceUpdateEvent e) {
        if (VaultProxy.inCall()) return; // the proxy recorded it with the real source
        BigDecimal oldBalance = e.getOldBalance();
        BigDecimal newBalance = e.getNewBalance();
        if (oldBalance == null || newBalance == null) return;
        double change = newBalance.subtract(oldBalance).doubleValue();
        if (change == 0) return;
        Player player = e.getPlayer();
        if (player == null) return;
        String cause = e.getCause() == null ? "UNKNOWN" : e.getCause().name();
        String source;
        String detail = "Essentials " + cause;
        switch (cause) {
            case "COMMAND_PAY" -> source = Sources.ESSENTIALS_PAY;
            case "COMMAND_SELL" -> source = Sources.ESSENTIALS_SELL;
            case "COMMAND_ECO" -> {
                // A plugin's reward command (it called dispatchCommand), or a person who typed /eco.
                source = ledger.resolver().caller("Essentials").orElse(null);
                if (source == null) {
                    String typed = ledger.commands().currentSource();
                    source = typed != null ? typed : Sources.UNKNOWN;
                    detail = ledger.commands().currentCommand();
                }
            }
            default -> source = ledger.resolver().caller("Essentials").orElse(Sources.ESSENTIALS);
        }
        ledger.record(player.getUniqueId(), player.getName(), com.direk.dkledger.ledger.Account.WALLET, change, source, detail,
                newBalance.doubleValue());
    }
}
