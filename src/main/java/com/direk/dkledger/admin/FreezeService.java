package com.direk.dkledger.admin;

import com.direk.dkledger.DkLedger;
import com.direk.dkledger.ledger.LedgerStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frozen players can't send money to other players: their transfer commands are blocked, Vault payments to
 * transfer plugins (trades, auctions, player shops) fail, and dkBank refuses their bank transfers.
 * They can still earn and spend in shops, so a freeze never locks someone out of playing.
 */
public final class FreezeService implements Listener {

    private final DkLedger plugin;
    private final LedgerStore store;
    private final Map<UUID, LedgerStore.Frozen> frozen = new ConcurrentHashMap<>();
    private volatile List<String> blockedCommands = List.of();
    private volatile Set<String> blockedSources = Set.of();

    public FreezeService(DkLedger plugin, LedgerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void configure(List<String> commands, List<String> sources) {
        List<String> c = new ArrayList<>();
        for (String s : commands) c.add(s.toLowerCase(Locale.ROOT).replaceFirst("^/", "").trim());
        blockedCommands = List.copyOf(c);
        Set<String> src = ConcurrentHashMap.newKeySet();
        for (String s : sources) src.add(s.toLowerCase(Locale.ROOT));
        blockedSources = src;
    }

    public CompletableFuture<Void> load() {
        return store.frozen().thenAccept(list -> {
            frozen.clear();
            for (LedgerStore.Frozen f : list) frozen.put(f.uuid(), f);
        });
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.containsKey(uuid);
    }

    public Optional<LedgerStore.Frozen> get(UUID uuid) {
        return Optional.ofNullable(frozen.get(uuid));
    }

    public List<LedgerStore.Frozen> all() {
        return List.copyOf(frozen.values());
    }

    /** True if a Vault withdrawal for this player and source must be refused. */
    public boolean blocks(UUID player, String source) {
        return frozen.containsKey(player) && blockedSources.contains(source.toLowerCase(Locale.ROOT));
    }

    public CompletableFuture<Void> freeze(UUID uuid, String name, String by, String reason) {
        LedgerStore.Frozen f = new LedgerStore.Frozen(uuid, name, System.currentTimeMillis(), by, reason);
        frozen.put(uuid, f);
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) plugin.messages().send(online, "freeze.you-are-frozen");
        return store.freeze(f);
    }

    public CompletableFuture<Boolean> unfreeze(UUID uuid) {
        boolean was = frozen.remove(uuid) != null;
        Player online = Bukkit.getPlayer(uuid);
        if (was && online != null) plugin.messages().send(online, "freeze.you-are-unfrozen");
        return store.unfreeze(uuid).thenApply(removed -> removed || was);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!frozen.containsKey(e.getPlayer().getUniqueId())) return;
        String typed = e.getMessage().toLowerCase(Locale.ROOT).replaceFirst("^/", "").trim();
        int colon = typed.indexOf(':');
        int space = typed.indexOf(' ');
        if (colon > 0 && (space < 0 || colon < space)) typed = typed.substring(colon + 1); // essentials:pay -> pay
        for (String blocked : blockedCommands) {
            if (typed.equals(blocked) || typed.startsWith(blocked + " ")) {
                e.setCancelled(true);
                plugin.messages().send(e.getPlayer(), "freeze.blocked");
                return;
            }
        }
    }
}
