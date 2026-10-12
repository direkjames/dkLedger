package dev.direk.dkledger.audit.check;

import dev.direk.dkledger.audit.AuditContext;
import dev.direk.dkledger.audit.Check;
import dev.direk.dkledger.audit.Finding;
import dev.direk.dkledger.audit.Money;
import dev.direk.dkledger.audit.model.PriceEntry;
import dev.direk.dkledger.audit.model.Table;
import dev.direk.dkledger.audit.read.SpawnerData;
import dev.direk.dkledger.audit.read.VirtualSpawnerReader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A6: how fast each spawner pays for itself, and whether its drops sell for more elsewhere.
 * Numbers are for one spawner with a player standing next to it; whether a stack multiplies the
 * drops depends on Virtual Spawner, so the per-chunk maximum assumes it does.
 */
public final class SpawnerCheck implements Check {

    @Override
    public String id() {
        return "A6";
    }

    private record Row(SpawnerData.Mob mob, double price, double perHour, double bestPerHour, double payback) {
    }

    @Override
    public void run(AuditContext ctx) {
        SpawnerData data = ctx.spawners;
        if (data == null || data.mobs().isEmpty()) return;
        int perChunk = Math.max(1, data.stackLimit()) * Math.max(1, data.chunkLimit());

        List<Row> rows = new ArrayList<>();
        List<Finding> elsewhere = new ArrayList<>();
        List<Finding> mismatch = new ArrayList<>();
        for (SpawnerData.Mob mob : data.mobs()) {
            double spawnsPerHour = 3600 / mob.secondsPerSpawn();
            double perHour = 0;
            double bestPerHour = 0;
            for (SpawnerData.Drop drop : mob.drops()) {
                double own = data.sellDisabled() ? 0 : drop.sellPrice();
                PriceEntry other = bestOutsideSpawners(ctx, drop.material());
                double otherPrice = other == null ? 0 : other.sell();
                perHour += drop.perSpawn() * own * spawnsPerHour;
                bestPerHour += drop.perSpawn() * Math.max(own, otherPrice) * spawnsPerHour;
                if (own > 0 && otherPrice >= own * ctx.settings.spawnerDropElsewhereRatio && drop.perSpawn() > 0) {
                    elsewhere.add(Finding.warning(id(),
                            drop.material() + " from " + mob.type() + " spawners sells " + Money.times(otherPrice / own)
                                    + " higher in " + other.source(),
                            "Virtual Spawner pays " + Money.format(own) + " each; " + other.source() + " pays "
                                    + Money.format(otherPrice) + " (" + other.where() + "). Players will take the drops out and sell them there: "
                                    + Money.format(drop.perSpawn() * otherPrice * spawnsPerHour) + "/h per spawner instead of "
                                    + Money.format(drop.perSpawn() * own * spawnsPerHour) + "/h.",
                            "Match the prices, or remove " + drop.material() + " from the " + mob.type() + " drops.",
                            mob.file()));
                }
            }
            Double shopPrice = ctx.shopSpawnerPrices.get(mob.type());
            double price = shopPrice != null ? shopPrice : mob.spawnerPrice();
            if (shopPrice != null && mob.spawnerPrice() > 0 && Math.abs(shopPrice - mob.spawnerPrice()) > shopPrice * 0.01) {
                mismatch.add(Finding.warning(id(), mob.type() + " spawner costs " + Money.format(shopPrice)
                                + " in the shop but " + Money.format(mob.spawnerPrice()) + " in Virtual Spawner",
                        "The two prices should match, or one of them is a typo.",
                        "Use the same price in both places.", mob.file()));
            }
            double earning = Math.max(perHour, bestPerHour);
            double payback = price > 0 && earning > 0 ? price / earning : Double.POSITIVE_INFINITY;
            rows.add(new Row(mob, price, perHour, bestPerHour, payback));
        }
        rows.sort(Comparator.comparingDouble(Row::payback));

        List<Finding> fast = new ArrayList<>();
        for (Row r : rows) {
            if (r.payback < ctx.settings.spawnerMinPaybackHours) {
                fast.add(Finding.warning(id(), r.mob.type() + " spawner pays for itself in " + Money.hours(r.payback),
                        "Costs " + Money.format(r.price) + " and earns about " + Money.format(Math.max(r.perHour, r.bestPerHour))
                                + "/h while a player is nearby. A full chunk (" + perChunk + " spawners) would earn "
                                + Money.format(Math.max(r.perHour, r.bestPerHour) * perChunk) + "/h if stacks multiply drops.",
                        "Raise the spawner price, lower its drop prices, or lower the stack and chunk limits. Warning starts at "
                                + Money.hours(ctx.settings.spawnerMinPaybackHours) + " (checks.spawners.min-payback-hours).",
                        r.mob.file()));
            }
        }
        ctx.addCapped(id(), fast, "spawners that pay for themselves quickly");
        ctx.addCapped(id(), elsewhere, "spawner drops that sell higher elsewhere");
        ctx.addCapped(id(), mismatch, "spawner price mismatches");

        if (!data.sellDisabled()) {
            ctx.add(Finding.warning(id(), "Virtual Spawner sells drops at fixed prices",
                    "Drops sold through Virtual Spawner skip EconomyShopGUI's dynamic pricing, so selling thousands doesn't lower the price.",
                    "Set disable-sell-feature: true in Virtual Spawner's config.yml so drops are sold in the shop.",
                    "Virtual Spawner/config.yml: disable-sell-feature"));
        }
        ctx.add(Finding.info(id(), "Up to " + perChunk + " spawners per chunk (" + data.stackLimit() + " per stack × "
                        + data.chunkLimit() + " stacks)",
                "There's no limit per island in Virtual Spawner itself. BentoBox's Limits addon can cap spawners per island.",
                "Virtual Spawner/config.yml: stack-limit, spawner-limit-in-chunk"));

        List<List<String>> table = new ArrayList<>();
        for (Row r : rows) {
            table.add(List.of(r.mob.type(), r.price > 0 ? Money.format(r.price) : "not sold",
                    Money.format(r.perHour), Money.format(r.bestPerHour), Money.hours(r.payback)));
        }
        ctx.tables.add(new Table("Spawners (one spawner, player nearby)",
                List.of("Mob", "Price", "$/h (Virtual Spawner)", "$/h (best market)", "Payback"), table));
    }

    private static PriceEntry bestOutsideSpawners(AuditContext ctx, String material) {
        PriceEntry best = null;
        for (PriceEntry e : ctx.prices.entries(material)) {
            if (e.source().equals(VirtualSpawnerReader.SOURCE) || !e.sellable()) continue;
            if (best == null || e.sell() > best.sell()) best = e;
        }
        return best;
    }
}
