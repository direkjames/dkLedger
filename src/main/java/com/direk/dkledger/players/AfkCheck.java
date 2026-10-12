package com.direk.dkledger.players;

import com.direk.dkledger.platform.Reflect;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.UUID;

/**
 * Whether a player is AFK: EssentialsX's AFK status when it's installed, otherwise (or as well) how long the
 * server says they've been idle. Read by name, so it works whichever version is installed.
 */
public final class AfkCheck {

    private volatile long idleMillis;

    public AfkCheck(int afkAfterMinutes) {
        configure(afkAfterMinutes);
    }

    public void configure(int afkAfterMinutes) {
        this.idleMillis = afkAfterMinutes <= 0 ? Long.MAX_VALUE : afkAfterMinutes * 60_000L;
    }

    /** Where the answer comes from, for the startup banner. */
    public String describe() {
        boolean ess = essentials() != null;
        String idle = idleMillis == Long.MAX_VALUE ? null : "idle " + (idleMillis / 60_000) + " min";
        if (ess && idle != null) return "EssentialsX AFK or " + idle;
        if (ess) return "EssentialsX AFK";
        return idle != null ? idle : "off";
    }

    public boolean isAfk(Player p) {
        Plugin ess = essentials();
        if (ess != null) {
            Object user = Reflect.call(ess, "getUser", Player.class, p);
            if (user == null) user = Reflect.call(ess, "getUser", UUID.class, p.getUniqueId());
            if (Reflect.call(user, "isAfk") instanceof Boolean afk && afk) return true;
        }
        if (idleMillis != Long.MAX_VALUE && Reflect.call(p, "getIdleDuration") instanceof Duration idle) {
            return idle.toMillis() >= idleMillis;
        }
        return false;
    }

    private static Plugin essentials() {
        Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        return ess != null && ess.isEnabled() ? ess : null;
    }
}
