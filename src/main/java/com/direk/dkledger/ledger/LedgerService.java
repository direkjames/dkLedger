package com.direk.dkledger.ledger;

import com.direk.dkledger.DkLedger;
import com.direk.dkledger.detect.AlertDraft;
import com.direk.dkledger.detect.LiveWatch;
import com.direk.dkledger.ledger.hook.VaultProxy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * The ledger: every money change from every hook comes here. Entries are queued in memory and saved in
 * batches (every few seconds) with their daily totals. Can be called from any thread.
 */
public final class LedgerService implements VaultProxy.Recorder {

    private final DkLedger plugin;
    private final LedgerStore store;
    private final SourceResolver resolver;
    private final CommandTracker commands;
    private final TransferPairer pairer = new TransferPairer(2_000, 0.25);
    private final ConcurrentLinkedQueue<LedgerEntry> queue = new ConcurrentLinkedQueue<>();
    private final Set<String> newSources = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private volatile Sources sources;
    private volatile RollupBuffer rollup;
    private volatile LiveWatch watch;
    private volatile boolean enabled = true;
    private final Object watchLock = new Object();

    public LedgerService(DkLedger plugin, LedgerStore store, SourceResolver resolver, CommandTracker commands) {
        this.plugin = plugin;
        this.store = store;
        this.resolver = resolver;
        this.commands = commands;
    }

    /** Applies config.yml. Called on enable and reload. */
    public void configure(boolean enabled, Sources sources, RollupBuffer rollup, LiveWatch watch) {
        flush(); // totals collected under the old settings go out first
        this.enabled = enabled;
        this.sources = sources;
        this.rollup = rollup;
        this.watch = watch;
    }

    public SourceResolver resolver() {
        return resolver;
    }

    public CommandTracker commands() {
        return commands;
    }

    public Sources sources() {
        return sources;
    }

    public void rememberName(UUID uuid, String name) {
        if (name != null && !name.isEmpty()) names.put(uuid, name);
    }

    /** A known name for a player, or the start of their UUID. */
    public String name(UUID uuid) {
        String n = names.get(uuid);
        if (n != null) return n;
        Player online = Bukkit.getPlayer(uuid);
        return online != null ? online.getName() : "";
    }

    // ------------------------------------------------------------------ Vault proxy

    @Override
    public String source(String method) {
        return resolver.caller().orElseGet(() -> {
            String typed = commands.currentSource();
            return typed != null ? typed : Sources.UNKNOWN;
        });
    }

    @Override
    public boolean blocked(UUID player, String source) {
        return plugin.freezes().blocks(player, source);
    }

    @Override
    public void record(UUID player, String name, double amount, String source, String detail, double balance) {
        record(player, name, Account.WALLET, amount, source, detail, balance);
    }

    // ------------------------------------------------------------------ recording

    /** Records a change. For transfer sources, the other player is found by matching the two halves. */
    public void record(UUID player, String name, Account account, double amount, String source, String detail, double balance) {
        recordPaired(player, name, account, amount, source, detail, balance, null);
    }

    /** Records a change whose other player may already be known (dkBank transfers). */
    public void recordPaired(UUID player, String name, Account account, double amount, String source, String detail,
                             double balance, UUID other) {
        if (!enabled) return;
        try {
            long now = System.currentTimeMillis();
            String n = name == null || name.isEmpty() ? name(player) : name;
            rememberName(player, n);
            UUID counterpart = other;
            if (counterpart == null && sources.isTransfer(source)) {
                if (amount < 0) pairer.sent(source, player, -amount, now);
                else counterpart = pairer.received(source, player, amount, now);
            }
            LedgerEntry entry = new LedgerEntry(0, now, player, n, account, amount, source, detail == null ? "" : detail,
                    counterpart, balance);
            queue.add(entry);
            rollup.add(entry);
            newSources.add(source);
            List<AlertDraft> drafts;
            synchronized (watchLock) {
                drafts = watch.check(entry, n);
            }
            if (!drafts.isEmpty()) plugin.alerts().raise(drafts);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't record a money change", e);
        }
    }

    /** Saves everything queued. Runs every few seconds and on shutdown. */
    public void flush() {
        RollupBuffer r = rollup;
        if (r == null) return;
        List<LedgerEntry> batch = new ArrayList<>();
        LedgerEntry e;
        while ((e = queue.poll()) != null) batch.add(e);
        RollupBuffer.Drained drained = r.drain();
        if (!batch.isEmpty() || !drained.isEmpty()) {
            store.save(batch, drained).whenComplete((v, err) -> {
                if (err != null) plugin.getLogger().log(Level.SEVERE, "Couldn't save " + batch.size() + " ledger entries", err);
            });
        }
        if (!newSources.isEmpty()) {
            Set<String> seen = new HashSet<>(newSources);
            newSources.removeAll(seen);
            store.touchSources(seen, System.currentTimeMillis());
        }
    }

    /** Saves everything and waits (on shutdown, from the main thread; the database has its own thread). */
    public void flushAndWait() {
        flush();
        try {
            store.meta("last-flush", String.valueOf(System.currentTimeMillis())).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("The last ledger entries may not have been saved: " + e.getMessage());
        }
    }

    /** Runs something on the live checks, safely (they're shared with the recording threads). */
    public void watch(java.util.function.Consumer<LiveWatch> action) {
        synchronized (watchLock) {
            if (watch != null) action.accept(watch);
        }
    }

    /** Entries waiting to be saved (for the banner and /ledger status). */
    public int pending() {
        return queue.size();
    }
}
