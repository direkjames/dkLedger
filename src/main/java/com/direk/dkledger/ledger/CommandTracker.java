package com.direk.dkledger.ledger;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Remembers who typed the command running right now, so a money change from a command nobody's plugin
 * dispatched (e.g. a staff member's /eco give) is put down to that staff member or the console.
 */
public final class CommandTracker implements Listener {

    private volatile String sender;
    private volatile String command;
    private volatile int tick = -1;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        remember(e.getPlayer().getName(), e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent e) {
        remember(Sources.CONSOLE, "/" + e.getCommand());
    }

    private void remember(String who, String text) {
        sender = who;
        command = text.length() > 120 ? text.substring(0, 120) : text;
        tick = Bukkit.getCurrentTick();
    }

    /** The source for a typed command in this tick: "staff:Name", "console", or null if nobody typed one. */
    public String currentSource() {
        if (!Bukkit.isPrimaryThread() || tick != Bukkit.getCurrentTick() || sender == null) return null;
        return sender.equals(Sources.CONSOLE) ? Sources.CONSOLE : Sources.staff(sender);
    }

    public String currentCommand() {
        return command == null ? "" : command;
    }
}
