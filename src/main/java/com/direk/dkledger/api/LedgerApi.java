package com.direk.dkledger.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
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
 * <b>Reporting to dkLedger</b> (the dk suite reporting hook): other plugins can say <i>why</i> money moved
 * ({@link #label}, {@link #expect}), report item trades dkLedger can't see ({@link #itemTrade}) and report
 * progress that isn't money ({@link #progress}). All of these can be called from any thread and never throw.
 * <pre>
 * try (LedgerApi.Label l = ledger.label("quest: daily_3")) {
 *     economy.depositPlayer(player, 500);   // recorded with the reason "quest: daily_3"
 * }
 * </pre>
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

    // ------------------------------------------------------------------ reporting to dkLedger (since 0.3.0)

    /** An open label; close it when the money calls are done. Closing twice is harmless. */
    interface Label extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Every money change made on this thread until the label is closed gets this reason (64 characters max).
     * Use for Vault calls, which happen right away. Labels can be nested; the innermost one wins.
     */
    Label label(String reason);

    /**
     * The player's next money change within 5 seconds gets this reason. Use when the money moves later or on
     * another thread: through a command, or through dkBank.
     */
    void expect(UUID player, String reason);

    /**
     * An item trade dkLedger can't see by itself (Lodestock and EconomyShopGUI are recorded already).
     *
     * @param plugin   your plugin (its name becomes the source)
     * @param item     the item, e.g. "diamond" or "DIAMOND"
     * @param bought   true if the player bought it, false if they sold it
     * @param quantity how many
     * @param money    what they paid or got (positive)
     */
    void itemTrade(Plugin plugin, UUID player, String item, boolean bought, int quantity, double money);

    /**
     * Progress that isn't money, kept forever to compare how players progress: a quest done, a rank, a level.
     * OneBlock phases and dkBank tiers are recorded already.
     *
     * @param type  what kind of thing ("quest", "rank", "island-level")
     * @param key   which one ("daily_3", "vip")
     * @param value a number that goes with it, or 0
     */
    void progress(Plugin plugin, UUID player, String type, String key, double value);

    // ------------------------------------------------------------------ items, reasons, activity (since 0.3.0)

    /** One item's trades with shops over a period. */
    record Item(String item, long bought, double boughtMoney, long sold, double soldMoney) {
    }

    /** Items traded over the last {@code days} days, most money first. */
    CompletableFuture<List<Item>> items(int days);

    /** Money for one reason (as reported with {@link #label} or {@link #expect}). */
    record Reason(String source, String reason, double gained, double lost) {
    }

    /** Reasons over the last {@code days} days, biggest first. */
    CompletableFuture<List<Reason>> reasons(int days);

    /**
     * How a player plays.
     *
     * @param firstJoin     their first join on the server (epoch millis), 0 if unknown
     * @param activeMinutes minutes active in the last 7 days
     * @param afkMinutes    minutes AFK in the last 7 days
     * @param sessions      joins in the last 7 days
     * @param daysPlayed    days they played in the last 7 days
     * @param progress      their newest progress events, newest first ("oneblock-phase plains (700)")
     */
    record Activity(UUID uuid, String name, long firstJoin, int activeMinutes, int afkMinutes, int sessions, int daysPlayed,
                    List<String> progress) {
    }

    CompletableFuture<Optional<Activity>> activity(String name);
}
