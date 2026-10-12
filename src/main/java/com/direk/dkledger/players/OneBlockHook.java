package com.direk.dkledger.players;

import com.direk.dkledger.platform.Reflect;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * AOneBlock phase changes ("this island reached the Plains phase at block 700"), recorded as progress events.
 * AOneBlock is a BentoBox addon, so its event class is found through BentoBox's addon manager, by name.
 */
public final class OneBlockHook implements Listener {

    public static final String EVENT = "world.bentobox.aoneblock.events.MagicBlockPhaseEvent";

    private final Consumer<ProgressEvent> sink;

    private OneBlockHook(Consumer<ProgressEvent> sink) {
        this.sink = sink;
    }

    /** False if BentoBox, AOneBlock or the event isn't there. Call after BentoBox has loaded its addons. */
    public static boolean install(Plugin owner, Consumer<ProgressEvent> sink) {
        Plugin bento = Bukkit.getPluginManager().getPlugin("BentoBox");
        if (bento == null || !bento.isEnabled()) return false;
        Object addons = Reflect.call(bento, "getAddonsManager");
        Object found = Reflect.call(addons, "getAddonByName", String.class, "AOneBlock");
        Object addon = found instanceof Optional<?> o ? o.orElse(null) : found;
        if (addon == null) return false;
        Class<? extends Event> type = Reflect.eventClass(addon.getClass().getClassLoader(), EVENT);
        if (type == null) return false;
        OneBlockHook hook = new OneBlockHook(sink);
        Reflect.listen(owner, hook, type, hook::onPhase);
        return true;
    }

    private void onPhase(Event e) {
        Object who = Reflect.call(e, "getPlayerUUID");
        if (!(who instanceof UUID)) who = Reflect.call(Reflect.call(e, "getIsland"), "getOwner"); // the island owner
        if (!(who instanceof UUID player)) return;
        String phase = Reflect.text(Reflect.call(e, "getPhase"));
        if (phase.isEmpty()) return;
        double block = Reflect.number(Reflect.call(e, "getBlockNumber"), 0);
        OfflinePlayer p = Bukkit.getOfflinePlayer(player);
        sink.accept(new ProgressEvent(System.currentTimeMillis(), player, p.getName(), "AOneBlock", "oneblock-phase", phase, block));
    }
}
