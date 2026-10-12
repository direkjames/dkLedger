package com.direk.dkledger.ledger.hook;

import com.direk.dkledger.admin.FreezeService;
import com.direk.dkledger.ledger.Account;
import com.direk.dkledger.ledger.LedgerService;
import com.direk.dkledger.ledger.Sources;
import dev.direk.dkbank.api.event.BankPreTransactionEvent;
import dev.direk.dkbank.api.event.BankTransactionEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * dkBank tells us about every bank change. Bank deposits and withdrawals move money between a player's own
 * wallet and bank (source "dkBank", which nets to the withdraw fee); interest creates money; upgrades and
 * fees destroy it; staff and plugin changes are put down to them.
 * <p>
 * Also stops frozen players sending bank money to others. Only loaded when dkBank is enabled.
 */
public final class DkBankHook implements Listener {

    private final LedgerService ledger;
    private final FreezeService freezes;

    public DkBankHook(LedgerService ledger, FreezeService freezes) {
        this.ledger = ledger;
        this.freezes = freezes;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTransaction(BankTransactionEvent e) {
        double amount = e.getAmount().doubleValue();
        double fee = e.getFee() == null ? 0 : e.getFee().doubleValue();
        String by = e.getSource();
        double change;
        String source;
        UUID other = e.getOther();
        switch (e.getKind()) {
            case DEPOSIT -> { change = amount; source = Sources.BANK; }
            case WITHDRAW -> { change = -amount; source = Sources.BANK; } // the fee stays out of the wallet's side
            case REFUND -> { change = amount; source = Sources.BANK; }
            case TRANSFER_OUT -> { change = -(amount + fee); source = Sources.BANK_TRANSFERS; }
            case TRANSFER_IN -> { change = amount; source = Sources.BANK_TRANSFERS; }
            case INTEREST -> { change = amount; source = Sources.BANK_INTEREST; }
            case UPGRADE -> { change = -amount; source = Sources.BANK_UPGRADES; }
            case ADMIN_GIVE -> { change = amount; source = staff(by); }
            case ADMIN_TAKE -> { change = -amount; source = staff(by); }
            case ADMIN_SET -> {
                // The event has the new balance, not the change: the next snapshot shows the difference.
                change = 0;
                source = staff(by);
            }
            case PLUGIN_GIVE -> { change = amount; source = by == null || by.isBlank() ? "unknown plugin" : by; }
            case PLUGIN_TAKE -> { change = -amount; source = by == null || by.isBlank() ? "unknown plugin" : by; }
            default -> { change = 0; source = Sources.BANK; }
        }
        String detail = "bank " + e.getKind().name().toLowerCase() + (fee > 0 ? " (fee " + fee + ")" : "")
                + (e.getKind().name().equals("ADMIN_SET") ? " to " + amount : "");
        String name = e.getPlayer() != null ? e.getPlayer().getName() : ledger.name(e.getAccount());
        if (change == 0 && !e.getKind().name().equals("ADMIN_SET")) return;
        ledger.recordPaired(e.getAccount(), name, Account.BANK, change, source, detail, e.getBalance().doubleValue(), other);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreTransaction(BankPreTransactionEvent e) {
        if (e.getAction() != BankPreTransactionEvent.Action.TRANSFER) return;
        if (!freezes.isFrozen(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        e.setCancelMessage(Component.text("Your money is frozen by staff, so you can't send it to others right now."));
    }

    private static String staff(String by) {
        if (by == null || by.isBlank()) return Sources.CONSOLE;
        return by.equalsIgnoreCase("console") ? Sources.CONSOLE : Sources.staff(by);
    }
}
