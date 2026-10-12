package com.direk.dkledger.detect;

import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.Severity;
import com.direk.dkledger.supply.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The checks that run after each money supply snapshot: B1 (supply growth), B2 (sources and sinks),
 * B3 (players far above the median today), B7 (wealth concentration) and B8 (price index).
 * Pure: every number comes in as an argument.
 */
public final class HourlyChecks {

    private static final long DAY = 86_400_000L;

    private HourlyChecks() {
    }

    /**
     * @param now        the snapshot just taken
     * @param weekAgo    the snapshot closest to 7 days ago, or null
     * @param installed  when dkLedger first ran (epoch millis), for the warm-up period
     * @param sourceNet  per source over the last 7 days: net money created (+) or destroyed (−);
     *                   transfer, internal and staff sources already left out
     * @param earnedToday per player name: money earned today (earning sources only)
     * @param prices     per Lodestock item: {price now, price {@code priceChangeDays} ago}
     */
    public static List<AlertDraft> run(Thresholds t, Snapshot now, Snapshot weekAgo, long installed,
                                       Map<String, Double> sourceNet, Map<String, Double> earnedToday,
                                       Map<String, double[]> prices) {
        List<AlertDraft> out = new ArrayList<>();
        boolean warm = now.time() - installed < t.warmupDays() * DAY;

        // B1: money supply growth.
        if (!warm && weekAgo != null && now.time() - weekAgo.time() >= 3 * DAY) {
            double days = (now.time() - weekAgo.time()) / (double) DAY;
            double growth = SupplyMath.dailyGrowth(weekAgo.total(), now.total(), days);
            Severity s = growth >= t.supplyGrowthCrit() ? Severity.CRITICAL : growth >= t.supplyGrowthWarn() ? Severity.WARNING : null;
            if (s != null) {
                out.add(new AlertDraft("B1", s, "server", "Money supply grows " + Money.percent(growth) + " a day",
                        "From " + Money.format(weekAgo.total()) + " to " + Money.format(now.total()) + " in " + Math.round(days)
                                + " days. Warning at " + Money.percent(t.supplyGrowthWarn()) + ", critical at "
                                + Money.percent(t.supplyGrowthCrit()) + ". /ledger sources week shows where it comes from."));
            }
            double perPlayer = weekAgo.perActive() > 0 ? now.perActive() / weekAgo.perActive() - 1 : 0;
            Severity p = perPlayer >= t.perPlayerGrowthCrit() ? Severity.CRITICAL : perPlayer >= t.perPlayerGrowthWarn() ? Severity.WARNING : null;
            if (p != null) {
                out.add(new AlertDraft("B1", p, "per-player", "Money per active player up " + Money.percent(perPlayer) + " this week",
                        "From " + Money.format(weekAgo.perActive()) + " to " + Money.format(now.perActive()) + " per active player."));
            }
        }

        // B2: one source making most of the new money, and too few sinks.
        double created = sourceNet.values().stream().filter(v -> v > 0).mapToDouble(Double::doubleValue).sum();
        double destroyed = -sourceNet.values().stream().filter(v -> v < 0).mapToDouble(Double::doubleValue).sum();
        if (!warm && created >= t.sourceShareMinNewMoney()) {
            for (Map.Entry<String, Double> e : sourceNet.entrySet()) {
                double share = e.getValue() / created;
                Severity s = share >= t.sourceShareCrit() ? Severity.CRITICAL : share >= t.sourceShareWarn() ? Severity.WARNING : null;
                if (s != null) {
                    out.add(new AlertDraft("B2", s, e.getKey(), e.getKey() + " made " + Money.percent(share) + " of all new money this week",
                            Money.format(e.getValue()) + " of " + Money.format(created) + " created in the last 7 days."));
                }
            }
            double ratio = destroyed / created;
            Severity s = ratio < t.sinkRatioCrit() ? Severity.CRITICAL : ratio < t.sinkRatioWarn() ? Severity.WARNING : null;
            if (s != null) {
                out.add(new AlertDraft("B2", s, "sinks", "Sinks removed only " + Money.percent(ratio) + " of the money added this week",
                        Money.format(created) + " added, " + Money.format(destroyed) + " removed. Warning below "
                                + Money.percent(t.sinkRatioWarn()) + ". More shop items, fees or upgrades would help."));
            }
        }

        // B3: players far above the median today.
        if (earnedToday.size() >= t.medianMinEarners()) {
            double median = SupplyMath.median(earnedToday.values().stream().mapToDouble(Double::doubleValue).toArray());
            if (median > 0) {
                for (Map.Entry<String, Double> e : earnedToday.entrySet()) {
                    double ratio = e.getValue() / median;
                    if (e.getValue() < t.medianMinEarned()) continue;
                    Severity s = ratio >= t.medianCrit() ? Severity.CRITICAL : ratio >= t.medianWarn() ? Severity.WARNING : null;
                    if (s != null) {
                        out.add(new AlertDraft("B3", s, e.getKey(), e.getKey() + " earned " + Money.times(ratio) + " the median today",
                                Money.format(e.getValue()) + " today; the median player earned " + Money.format(median) + "."));
                    }
                }
            }
        }

        // B7: wealth concentration.
        if (now.accounts() >= t.topShareMinAccounts()) {
            Severity s = now.topShare() >= t.topShareCrit() ? Severity.CRITICAL : now.topShare() >= t.topShareWarn() ? Severity.WARNING : null;
            if (s != null) {
                out.add(new AlertDraft("B7", s, "server", "The richest 10% hold " + Money.percent(now.topShare()) + " of all money",
                        "Across " + now.accounts() + " players. Gini " + String.format(java.util.Locale.US, "%.2f", now.gini())
                                + " (0 = equal, 1 = one player has everything)."));
            }
        }

        // B8: price index.
        for (Map.Entry<String, double[]> e : prices.entrySet()) {
            double then = e.getValue()[1];
            if (then <= 0) continue;
            double change = e.getValue()[0] / then - 1;
            double abs = Math.abs(change);
            Severity s = abs >= t.priceChangeCrit() ? Severity.CRITICAL : abs >= t.priceChangeWarn() ? Severity.WARNING : null;
            if (s != null) {
                out.add(new AlertDraft("B8", s, e.getKey(), e.getKey() + " price " + (change > 0 ? "up " : "down ") + Money.percent(abs)
                                + " in " + t.priceChangeDays() + " days",
                        "Lodestock price " + Money.format(then) + " → " + Money.format(e.getValue()[0]) + ". "
                                + (change > 0 ? "Rising prices mean money is worth less (inflation) or the item is scarce."
                                : "Falling prices mean the market is flooded with it.")));
            }
        }
        return out;
    }
}
