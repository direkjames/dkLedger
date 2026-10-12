package com.direk.dkledger.audit.check;

import com.direk.dkledger.audit.AuditContext;
import com.direk.dkledger.audit.Check;
import com.direk.dkledger.audit.Finding;
import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.model.PriceEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A1, A2 and A5: checks on prices alone, no recipes needed. */
public final class PriceChecks {

    private PriceChecks() {
    }

    /** A1: an item that sells for as much as (or more than) it costs in the same shop. */
    public static final class SellAboveBuy implements Check {
        @Override
        public String id() {
            return "A1";
        }

        @Override
        public void run(AuditContext ctx) {
            List<Finding> found = new ArrayList<>();
            List<PriceEntry> loops = ctx.prices.all().stream()
                    .filter(e -> e.buyable() && e.sellable() && e.sell() >= e.buy())
                    .sorted(Comparator.comparingDouble((PriceEntry e) -> e.sell() - e.buy()).reversed())
                    .toList();
            for (PriceEntry e : loops) {
                double profit = e.sell() - e.buy();
                String detail = "Buy " + Money.format(e.buy()) + ", sell " + Money.format(e.sell()) + " in " + e.source()
                        + (profit > 0 ? ": +" + Money.format(profit) + " per item, as often as a player likes." : ": no profit, but no loss either.");
                Finding f = profit > 0
                        ? Finding.critical(id(), e.material() + " sells for more than it costs (" + e.source() + ")", detail,
                        "Set the sell price below the buy price.", e.where())
                        : Finding.warning(id(), e.material() + " sells for exactly what it costs (" + e.source() + ")", detail,
                        "Set the sell price below the buy price.", e.where());
                found.add(f);
            }
            ctx.addCapped(id(), found, "items that sell at or above their buy price");
        }
    }

    /** A2: buy an item in one plugin and sell it in another for more. */
    public static final class CrossPluginArbitrage implements Check {
        @Override
        public String id() {
            return "A2";
        }

        @Override
        public void run(AuditContext ctx) {
            record Arb(PriceEntry buy, PriceEntry sell, double profit) {
            }
            List<Arb> arbs = new ArrayList<>();
            for (String material : ctx.prices.materials()) {
                Optional<PriceEntry> cheapest = ctx.prices.cheapestBuy(material);
                if (cheapest.isEmpty()) continue;
                PriceEntry buy = cheapest.get();
                PriceEntry bestSell = null;
                for (PriceEntry e : ctx.prices.entries(material)) {
                    if (!e.sellable() || e.source().equals(buy.source())) continue;
                    if (bestSell == null || e.sell() > bestSell.sell()) bestSell = e;
                }
                if (bestSell == null) continue;
                double profit = bestSell.sell() - buy.buy();
                if (profit >= ctx.settings.minLoopProfit) arbs.add(new Arb(buy, bestSell, profit));
            }
            arbs.sort(Comparator.comparingDouble(Arb::profit).reversed());
            List<Finding> found = new ArrayList<>();
            for (Arb a : arbs) {
                found.add(Finding.critical(id(),
                        a.buy.material() + ": buy in " + a.buy.source() + ", sell in " + a.sell.source(),
                        "Buy for " + Money.format(a.buy.buy()) + " (" + a.buy.where() + "), sell for "
                                + Money.format(a.sell.sell()) + " (" + a.sell.where() + "): +" + Money.format(a.profit)
                                + " per item." + (a.buy.source().equals("Lodestock") || a.sell.source().equals("Lodestock") ? " Lodestock prices move with stock, so the real gap may be smaller." : ""),
                        "Raise the buy price in " + a.buy.source() + " or lower the sell price in " + a.sell.source() + ".",
                        a.sell.where()));
            }
            ctx.addCapped(id(), found, "items that can be bought in one plugin and sold in another for profit");
        }
    }

    /** A5: items players can make forever with a generator or farm, sold for more than a fair price. */
    public static final class RenewableValue implements Check {
        @Override
        public String id() {
            return "A5";
        }

        @Override
        public void run(AuditContext ctx) {
            List<Finding> found = new ArrayList<>();
            for (Map.Entry<String, Double> limit : ctx.settings.renewableMaxSell.entrySet()) {
                Optional<PriceEntry> sell = ctx.prices.bestSell(limit.getKey());
                if (sell.isEmpty() || sell.get().sell() <= limit.getValue()) continue;
                PriceEntry e = sell.get();
                double ratio = e.sell() / Math.max(0.0001, limit.getValue());
                String title = e.material() + " sells for " + Money.format(e.sell()) + " but players can make it forever";
                String detail = "Generators and farms make " + e.material() + " without limit. It sells for "
                        + Money.format(e.sell()) + " in " + e.source() + ", " + Money.times(ratio)
                        + " the fair price of " + Money.format(limit.getValue()) + " set in checks.renewables.";
                String fix = "Lower the sell price to " + Money.format(limit.getValue()) + " or less, or stop it being sold.";
                found.add(ratio >= 5 ? Finding.critical(id(), title, detail, fix, e.where())
                        : Finding.warning(id(), title, detail, fix, e.where()));
            }
            ctx.addCapped(id(), found, "renewable items sold too high");
        }
    }
}
