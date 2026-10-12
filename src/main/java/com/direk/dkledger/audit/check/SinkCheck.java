package com.direk.dkledger.audit.check;

import com.direk.dkledger.audit.AuditContext;
import com.direk.dkledger.audit.Check;
import com.direk.dkledger.audit.Finding;
import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.model.PriceEntry;
import com.direk.dkledger.audit.model.Sink;
import com.direk.dkledger.audit.model.Table;
import com.direk.dkledger.audit.read.EsguiReader;
import com.direk.dkledger.audit.yaml.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A11: every way money leaves the economy, and whether players keep paying it or pay once. */
public final class SinkCheck implements Check {

    @Override
    public String id() {
        return "A11";
    }

    @Override
    public void run(AuditContext ctx) {
        // Shop purchases
        List<PriceEntry> buyable = ctx.prices.all().stream()
                .filter(e -> e.source().equals(EsguiReader.SOURCE) && e.buyable()).toList();
        if (!buyable.isEmpty()) {
            ctx.sinks.addFirst(new Sink(EsguiReader.SOURCE, "Shop purchases", buyable.size() + " items, "
                    + Money.format(buyable.stream().mapToDouble(PriceEntry::buy).min().orElse(0)) + " to "
                    + Money.format(buyable.stream().mapToDouble(PriceEntry::buy).max().orElse(0)), true));
        }
        if (!ctx.shopSpawnerPrices.isEmpty()) {
            ctx.sinks.add(new Sink(EsguiReader.SOURCE, "Spawners", ctx.shopSpawnerPrices.size() + " spawners, "
                    + Money.format(ctx.shopSpawnerPrices.values().stream().mapToDouble(Double::doubleValue).min().orElse(0)) + " to "
                    + Money.format(ctx.shopSpawnerPrices.values().stream().mapToDouble(Double::doubleValue).max().orElse(0)), false));
        }

        // Plugins with a fee or price in their config. Missing plugins are simply skipped.
        Map<String, Object> warps = quiet(ctx, ctx.settings.playerWarpsConfig);
        if (warps != null && Node.bool(Node.at(warps, "warp-creation-cost.enabled"), false)) {
            ctx.sinks.add(new Sink("AxPlayerWarps", "Create a warp", Money.format(Node.num(Node.at(warps, "warp-creation-cost.price"), 0)), false));
        }
        Map<String, Object> auctions = quiet(ctx, ctx.settings.auctionsCurrencies);
        double auctionTax = Node.num(Node.at(auctions, "currencies.Vault.tax"), 0);
        if (auctionTax > 0) ctx.sinks.add(new Sink("AxAuctions", "Auction tax", auctionTax + "% of each sale", true));

        Path lodestock = ctx.find(ctx.settings.lodestockFolder);
        if (lodestock != null) {
            double tax = Node.num(Node.child(ctx.readQuietly(lodestock.resolve("config.yml")), "tax-percent"), 0);
            if (tax > 0) ctx.sinks.add(new Sink("Lodestock", "Market tax", tax + "% of each sale", true));
        }
        Map<String, Object> heads = quiet(ctx, ctx.settings.headDatabaseConfig);
        if (heads != null && Node.bool(Node.child(heads, "Economy-Enabled"), false)) {
            double min = Double.MAX_VALUE;
            double max = 0;
            for (Map.Entry<String, Object> e : heads.entrySet()) {
                if (!e.getKey().endsWith("-price")) continue;
                double p = Node.num(e.getValue(), 0);
                if (p <= 0) continue;
                min = Math.min(min, p);
                max = Math.max(max, p);
            }
            if (max > 0) ctx.sinks.add(new Sink("HeadDatabase", "Decorative heads", Money.format(min) + " to " + Money.format(max) + " per head", true));
        }
        Path addons = ctx.find(ctx.settings.bentoBoxAddonsFolder);
        if (addons != null) {
            Map<String, Object> visit = ctx.readQuietly(addons.resolve("Visit/config.yml"));
            double tax = Node.num(Node.child(visit, "traveling-tax"), 0);
            if (tax > 0) ctx.sinks.add(new Sink("BentoBox Visit", "Visiting an island", Money.format(tax) + " per visit", true));
            Map<String, Object> likes = ctx.readQuietly(addons.resolve("Likes/config.yml"));
            double like = Node.num(Node.at(likes, "costs.add-like"), 0);
            if (like > 0) ctx.sinks.add(new Sink("BentoBox Likes", "Liking an island", Money.format(like) + " per like", true));
        }
        Map<String, Object> pinata = quiet(ctx, ctx.settings.pinataPartyConfig);
        double pool = Node.num(Node.at(pinata, "money-pool.money-needed"), 0);
        if (pool > 0) ctx.sinks.add(new Sink("PinataParty", "Pinata money pool", Money.format(pool) + " per pinata (shared)", true));

        if (ctx.sinks.isEmpty()) return;
        long repeating = ctx.sinks.stream().filter(Sink::repeating).count();
        ctx.add(Finding.info(id(), ctx.sinks.size() + " money sinks: " + repeating + " repeating, " + (ctx.sinks.size() - repeating) + " one-time",
                "Repeating sinks keep pulling money out as the server grows; one-time ones stop once everyone has bought them. See the sinks table.",
                ""));
        List<List<String>> rows = new ArrayList<>();
        for (Sink s : ctx.sinks) rows.add(List.of(s.name(), s.plugin(), s.amount(), s.repeating() ? "repeating" : "one-time"));
        ctx.tables.add(new Table("Money sinks", List.of("Sink", "Plugin", "Amount", "Kind"), rows));
    }

    private static Map<String, Object> quiet(AuditContext ctx, List<String> candidates) {
        Path p = ctx.find(candidates);
        return p == null ? null : ctx.readQuietly(p);
    }
}
