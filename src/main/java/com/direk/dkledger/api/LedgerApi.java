package com.direk.dkledger.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * dkLedger for other plugins, e.g. the dk suite's Discord bot. Everything /ledger can show or do.
 * <pre>
 * LedgerApi.find().ifPresent(ledger -&gt; ledger.supply().thenAccept(supply -&gt; ...));
 * </pre>
 * Futures complete on the main thread. Listen to {@link LedgerAlertEvent} for new alerts.
 * <p>
 * Author: direk james
 */
public interface LedgerApi {

    /** The API, or empty if dkLedger isn't running. */
    static Optional<LedgerApi> find() {
        RegisteredServiceProvider<LedgerApi> reg = Bukkit.getServicesManager().getRegistration(LedgerApi.class);
        return reg == null ? Optional.empty() : Optional.of(reg.getProvider());
    }

    /**
     * Money supply now and its change.
     *
     * @param wallet       money in wallets
     * @param bank         money in banks
     * @param change24h    change of the total over 24 hours (0.05 = +5%), NaN if no snapshot that old
     * @param change7d     change over 7 days, NaN if no snapshot that old
     * @param perActive    money per player seen in the last 24 hours
     * @param topShare     share held by the richest 10%
     * @param time         when the snapshot was taken (epoch millis), 0 if none yet
     */
    record Supply(double wallet, double bank, double change24h, double change7d, double perActive, double topShare, long time) {
        public double total() {
            return wallet + bank;
        }
    }

    /** One source's money over a period: positive net = it created money, negative = it removed money. */
    record Source(String name, double gained, double lost, String kind) {
        public double net() {
            return gained - lost;
        }
    }

    /** A player's money summary. */
    record Player(UUID uuid, String name, boolean frozen, String frozenReason, double earnedToday, double earned7d,
                  List<Source> sources7d, List<String> recentEntries, List<String> notes) {
    }

    CompletableFuture<Supply> supply();

    /** Sources over the last {@code days} days, biggest movers first. */
    CompletableFuture<List<Source>> sources(int days);

    CompletableFuture<Optional<Player>> player(String name);

    /** Alerts of the last 30 days, newest first. */
    List<AlertView> alerts();

    CompletableFuture<Boolean> resolveAlert(long id, String by, String note);

    CompletableFuture<Boolean> freeze(String player, String by, String reason);

    CompletableFuture<Boolean> unfreeze(String player);

    boolean isFrozen(UUID player);

    CompletableFuture<Boolean> note(String player, String by, String text);

    /** Undoes one ledger entry. The result is a message for whoever asked. */
    CompletableFuture<String> reverse(long entryId, String by);

    /** Runs the config audit; the result lines are a short summary. */
    CompletableFuture<List<String>> scan();

    /** Runs the "what if" simulator, e.g. {@code "price BASALT sell 0.18"}; returns the result as lines. */
    CompletableFuture<List<String>> whatIf(String arguments);
}
