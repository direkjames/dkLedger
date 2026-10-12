package com.direk.dkledger.admin;

import com.direk.dkledger.DkLedger;
import com.direk.dkledger.ledger.LedgerStore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which connections each player joined from, to spot money moving between alt accounts (B9).
 * Only a salted hash of each IP address is kept, never the address itself.
 */
public final class LoginTracker implements Listener {

    private final DkLedger plugin;
    private final LedgerStore store;
    private final Map<UUID, Set<String>> connections = new ConcurrentHashMap<>();
    private final String salt;

    public LoginTracker(DkLedger plugin, LedgerStore store, String salt) {
        this.plugin = plugin;
        this.store = store;
        this.salt = salt;
    }

    public CompletableFuture<Void> load() {
        return store.logins().thenAccept(map -> {
            connections.clear();
            map.forEach((uuid, hashes) -> connections.put(uuid, ConcurrentHashMap.newKeySet()));
            map.forEach((uuid, hashes) -> connections.get(uuid).addAll(hashes));
        });
    }

    /** Hashed connections of a player (empty if unknown). */
    public Set<String> of(UUID uuid) {
        return connections.getOrDefault(uuid, Set.of());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        plugin.ledger().rememberName(e.getPlayer().getUniqueId(), e.getPlayer().getName());
        InetSocketAddress address = e.getPlayer().getAddress();
        if (address == null || address.getAddress() == null) return;
        String hash = hash(address.getAddress().getHostAddress());
        connections.computeIfAbsent(e.getPlayer().getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(hash);
        store.touchLogin(e.getPlayer().getUniqueId(), hash, System.currentTimeMillis());
    }

    private String hash(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((salt + "|" + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
