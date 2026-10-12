package com.direk.dkledger.detect;

import com.direk.dkledger.DkLedger;
import com.direk.dkledger.ledger.LedgerStore;
import com.direk.dkledger.ledger.Sources;
import com.direk.dkledger.supply.Snapshot;
import org.bukkit.Bukkit;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Every hour: takes a money supply snapshot, samples Lodestock's prices, then runs the hourly checks
 * (B1, B2, B3, B7, B8). Once a day it deletes ledger entries older than the keep time.
 */
public final class DetectorService {

    private static final long DAY = 86_400_000L;

    private final DkLedger plugin;
    private final LedgerStore store;
    private volatile Thresholds thresholds = Thresholds.defaults();
    private volatile ZoneId zone = ZoneId.systemDefault();
    private volatile int keepDays = 30;
    private volatile long installed = System.currentTimeMillis();
    private long lastPrune;

    public DetectorService(DkLedger plugin, LedgerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void configure(Thresholds thresholds, ZoneId zone, int keepDays) {
        this.thresholds = thresholds;
        this.zone = zone;
        this.keepDays = Math.max(1, keepDays);
    }

    public void installed(long time) {
        this.installed = time;
    }

    public long installed() {
        return installed;
    }

    /** One hourly run. Main thread. */
    public CompletableFuture<Void> run() {
        long now = System.currentTimeMillis();
        plugin.watchPastWarmup(now - installed > DAY);
        Map<String, Double> pricesNow = Bukkit.getPluginManager().isPluginEnabled("Lodestock") ? safePrices() : Map.of();
        if (!pricesNow.isEmpty()) store.savePrices(now, pricesNow);
        Thresholds t = thresholds;
        String today = LocalDate.now(zone).toString();
        String weekStart = LocalDate.now(zone).minusDays(6).toString();
        Sources sources = plugin.ledger().sources();

        return plugin.supply().take().thenCompose(snap -> {
            if (snap.isEmpty()) return CompletableFuture.completedFuture(null);
            Snapshot s = snap.get();
            CompletableFuture<Optional<Snapshot>> weekAgo = store.snapshotAtOrBefore(now - 7 * DAY);
            CompletableFuture<List<LedgerStore.SourceTotal>> totals = store.sourceTotals(weekStart, today);
            CompletableFuture<Map<String, Double>> earned = store.earnedOn(today);
            CompletableFuture<Map<String, Double>> pricesThen = pricesNow.isEmpty()
                    ? CompletableFuture.completedFuture(Map.of()) : store.pricesAt(now - t.priceChangeDays() * DAY);
            return CompletableFuture.allOf(weekAgo, totals, earned, pricesThen).thenRun(() -> {
                Map<String, Double> net = new LinkedHashMap<>();
                for (LedgerStore.SourceTotal st : totals.join()) {
                    if (Sources.isStaff(st.source())) continue;
                    net.merge(st.source(), st.net(), Double::sum);
                }
                net.values().removeIf(v -> Math.abs(v) < 0.005);
                // Transfers and wallet/bank moves only count as their fees: keep them as sinks, never as faucets.
                net.entrySet().removeIf(e -> (sources.isTransfer(e.getKey()) || sources.isInternal(e.getKey())) && e.getValue() > 0);
                Map<String, double[]> prices = new HashMap<>();
                Map<String, Double> then = pricesThen.join();
                for (Map.Entry<String, Double> e : pricesNow.entrySet()) {
                    Double old = then.get(e.getKey());
                    if (old != null) prices.put(e.getKey(), new double[]{e.getValue(), old});
                }
                List<AlertDraft> drafts = HourlyChecks.run(t, s, weekAgo.join().orElse(null), installed, net, earned.join(), prices);
                if (!drafts.isEmpty()) plugin.alerts().raise(drafts);
            });
        }).whenComplete((v, err) -> {
            if (err != null) plugin.getLogger().log(Level.WARNING, "The hourly economy check failed", err);
            if (now - lastPrune > DAY) {
                lastPrune = now;
                store.prune(now - keepDays * DAY).thenAccept(n -> {
                    if (n > 0) plugin.debug("Deleted " + n + " ledger entries older than " + keepDays + " days");
                });
            }
        });
    }

    private Map<String, Double> safePrices() {
        try {
            return LodestockPrices.now();
        } catch (LinkageError | RuntimeException e) {
            plugin.debug("Couldn't read Lodestock prices: " + e);
            return Map.of();
        }
    }
}
