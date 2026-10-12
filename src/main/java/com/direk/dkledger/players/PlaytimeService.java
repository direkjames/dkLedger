package com.direk.dkledger.players;

import com.direk.dkledger.DkLedger;
import com.direk.dkledger.ledger.LedgerStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.logging.Level;

/**
 * Who plays and how much: each player's first join, and minutes active and AFK per day. This is what later
 * turns "money per player" into "money per hour played", and shows how new players progress.
 * <p>
 * Every minute, each online player gets one minute, active or AFK. Saved with the ledger.
 */
public final class PlaytimeService implements Listener {

    private final DkLedger plugin;
    private final LedgerStore store;
    private final PlaytimeBuffer buffer = new PlaytimeBuffer();
    private final AfkCheck afk = new AfkCheck(5);
    private volatile ZoneId zone = ZoneId.systemDefault();
    private volatile boolean enabled = true;

    public PlaytimeService(DkLedger plugin, LedgerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void configure(boolean enabled, int afkAfterMinutes, ZoneId zone) {
        flush();
        this.enabled = enabled;
        this.zone = zone;
        afk.configure(afkAfterMinutes);
    }

    public boolean enabled() {
        return enabled;
    }

    public AfkCheck afk() {
        return afk;
    }

    private String today() {
        return LocalDate.ofInstant(Instant.now(), zone).toString();
    }

    /** Players already online (after /reload or a late enable) are counted from now on. Main thread. */
    public void start() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) store.touchPlayer(p.getUniqueId(), p.getName(), firstPlayed(p, now), now);
    }

    /** Runs every minute on the main thread. */
    public void tick() {
        if (!enabled) return;
        String day = today();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean away;
            try {
                away = afk.isAfk(p);
            } catch (RuntimeException e) {
                away = false;
            }
            buffer.minute(day, p.getUniqueId(), away);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        buffer.joined(today(), p.getUniqueId());
        store.touchPlayer(p.getUniqueId(), p.getName(), firstPlayed(p, now), now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        store.touchPlayer(p.getUniqueId(), p.getName(), firstPlayed(p, now), now);
    }

    /** When the server first saw them (from before dkLedger was installed too); now if unknown. */
    private static long firstPlayed(Player p, long now) {
        long first = p.getFirstPlayed();
        return first > 0 && first <= now ? first : now;
    }

    public void flush() {
        var days = buffer.drain();
        if (days.isEmpty()) return;
        store.savePlaytime(days).whenComplete((v, err) -> {
            if (err != null) plugin.getLogger().log(Level.SEVERE, "Couldn't save playtime", err);
        });
    }
}
