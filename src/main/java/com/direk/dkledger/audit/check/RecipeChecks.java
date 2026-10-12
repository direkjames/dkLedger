package com.direk.dkledger.audit.check;

import com.direk.dkledger.audit.AuditContext;
import com.direk.dkledger.audit.Check;
import com.direk.dkledger.audit.Finding;
import com.direk.dkledger.audit.Money;
import com.direk.dkledger.audit.model.PriceEntry;
import com.direk.dkledger.audit.model.RecipeModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A3 and A4, using the server's real recipes (crafting, furnace, smoker, blast furnace, campfire,
 * stonecutter and smithing):
 * <ul>
 *   <li>A3: buy the ingredients, make the item, sell it for more than the ingredients cost: a money loop.</li>
 *   <li>A4: making the item turns cheap ingredients into something worth far more, or makes something
 *   sellable out of ingredients that can't be sold (e.g. unsellable wool into sellable carpets).</li>
 * </ul>
 * One step only: a loop through two recipes (A into B into C) isn't found yet.
 */
public final class RecipeChecks implements Check {

    @Override
    public String id() {
        return "A3";
    }

    private record Priced(RecipeModel recipe, double cost, double value, String inputs, String sellWhere) {
    }

    @Override
    public void run(AuditContext ctx) {
        if (ctx.server.recipes().isEmpty()) {
            ctx.notes.add("A3 and A4 were skipped: no recipes (the scan didn't run on a server).");
            return;
        }
        // Best (most profitable) recipe per result, so each item is reported once.
        Map<String, Priced> loops = new LinkedHashMap<>();
        Map<String, Priced> leaps = new LinkedHashMap<>();
        Map<String, Priced> onlyAfter = new LinkedHashMap<>();

        for (RecipeModel recipe : ctx.server.recipes()) {
            if (recipe.ingredients().isEmpty() || recipe.amount() <= 0) continue;
            if (usesItsResult(recipe)) continue; // dyeing, repairing: the result goes back in
            Optional<PriceEntry> sell = ctx.prices.bestSell(recipe.result());
            if (sell.isEmpty()) continue;
            double value = sell.get().sell() * recipe.amount();

            // A3: what the ingredients cost to buy.
            double cost = 0;
            boolean allBuyable = true;
            StringBuilder buyText = new StringBuilder();
            for (RecipeModel.Ingredient in : recipe.ingredients()) {
                PriceEntry cheapest = null;
                for (String choice : in.choices()) {
                    PriceEntry e = ctx.prices.cheapestBuy(choice).orElse(null);
                    if (e != null && (cheapest == null || e.buy() < cheapest.buy())) cheapest = e;
                }
                if (cheapest == null) {
                    allBuyable = false;
                    break;
                }
                cost += cheapest.buy() * in.count();
                append(buyText, in.count(), cheapest.material(), cheapest.buy(), cheapest.source());
            }
            if (allBuyable && value - cost >= ctx.settings.minLoopProfit) {
                keepBest(loops, new Priced(recipe, cost, value, buyText.toString(), sell.get().where()));
                continue;
            }

            // A4: what the ingredients sell for.
            double inValue = 0;
            boolean allSellable = true;
            StringBuilder sellText = new StringBuilder();
            for (RecipeModel.Ingredient in : recipe.ingredients()) {
                PriceEntry lowest = null;
                for (String choice : in.choices()) {
                    PriceEntry e = ctx.prices.bestSell(choice).orElse(null);
                    if (e != null && (lowest == null || e.sell() < lowest.sell())) lowest = e;
                }
                if (lowest == null) {
                    allSellable = false;
                    append(sellText, in.count(), String.join("/", shortList(in.choices())), 0, "can't be sold");
                    continue;
                }
                inValue += lowest.sell() * in.count();
                append(sellText, in.count(), lowest.material(), lowest.sell(), lowest.source());
            }
            if (allSellable) {
                if (inValue > 0 && value - inValue >= ctx.settings.valueLeapMinGain
                        && value / inValue >= ctx.settings.valueLeapRatio) {
                    keepBest(leaps, new Priced(recipe, inValue, value, sellText.toString(), sell.get().where()));
                }
            } else if (value >= ctx.settings.sellableOnlyAfterCraftingMin) {
                keepBest(onlyAfter, new Priced(recipe, 0, value, sellText.toString(), sell.get().where()));
            }
        }

        List<Finding> a3 = new ArrayList<>();
        for (Priced p : sorted(loops)) {
            RecipeModel r = p.recipe;
            a3.add(Finding.critical("A3",
                    "Buy, " + verb(r) + " and sell: " + r.amount() + "× " + r.result() + " for +" + Money.format(p.value - p.cost),
                    "Ingredients cost " + Money.format(p.cost) + " (" + p.inputs + "). " + gerund(r) + " them makes " + r.amount() + "× " + r.result() + ", which sells for "
                            + Money.format(p.value) + ". Recipe " + r.key() + ".",
                    "Lower the sell price of " + r.result() + " below " + Money.format(p.cost / r.amount())
                            + " each, or raise the ingredients' buy prices.",
                    p.sellWhere));
        }
        ctx.addCapped("A3", a3, "buy-make-sell loops");

        Set<String> reported = new HashSet<>(loops.keySet());
        List<Finding> a4 = new ArrayList<>();
        for (Priced p : sorted(leaps)) {
            if (!reported.add(p.recipe.result())) continue;
            RecipeModel r = p.recipe;
            a4.add(Finding.warning("A4",
                    r.result() + " is worth " + Money.times(p.value / p.cost) + " its ingredients",
                    "The ingredients sell for " + Money.format(p.cost) + " (" + p.inputs + "), but " + gerund(r).toLowerCase(java.util.Locale.ROOT) + " them makes "
                            + r.amount() + "× " + r.result() + " that sells for " + Money.format(p.value)
                            + ": +" + Money.format(p.value - p.cost) + " per " + verb(r) + ". Players will " + verb(r)
                            + " before selling. Recipe " + r.key() + ".",
                    "Lower the sell price of " + r.result() + " to about " + Money.format(p.cost / r.amount()) + " each.",
                    p.sellWhere));
        }
        for (Priced p : sorted(onlyAfter)) {
            if (!reported.add(p.recipe.result())) continue;
            RecipeModel r = p.recipe;
            a4.add(Finding.warning("A4",
                    r.result() + " sells for " + Money.format(p.value) + " though its ingredients can't be sold",
                    "Ingredients: " + p.inputs + ". " + gerund(r)
                            + " them makes " + r.amount() + "× " + r.result() + " worth " + Money.format(p.value)
                            + ", so turning off selling for the ingredients doesn't stop the money. Recipe " + r.key() + ".",
                    "Turn off selling for " + r.result() + " too, or price it like its ingredients.",
                    p.sellWhere));
        }
        a4.sort(Comparator.comparing(Finding::title));
        ctx.addCapped("A4", a4, "items that are worth much more after crafting");
    }

    private static boolean usesItsResult(RecipeModel r) {
        for (RecipeModel.Ingredient in : r.ingredients()) {
            if (in.choices().contains(r.result())) return true;
        }
        return false;
    }

    private static void keepBest(Map<String, Priced> map, Priced p) {
        Priced old = map.get(p.recipe.result());
        if (old == null || p.value - p.cost > old.value - old.cost) map.put(p.recipe.result(), p);
    }

    private static List<Priced> sorted(Map<String, Priced> map) {
        List<Priced> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparingDouble((Priced p) -> p.value - p.cost).reversed());
        return list;
    }

    private static void append(StringBuilder text, int count, String material, double price, String where) {
        if (!text.isEmpty()) text.append(", ");
        text.append(count).append("× ").append(material);
        if (price > 0) text.append(" @ ").append(Money.format(price)).append(" in ").append(where);
        else text.append(" (").append(where).append(")");
    }

    private static List<String> shortList(List<String> choices) {
        return choices.size() <= 3 ? choices : List.of(choices.get(0), choices.get(1), "…");
    }

    private static String gerund(RecipeModel r) {
        return switch (verb(r)) {
            case "smelt" -> "Smelting";
            case "cook" -> "Cooking";
            case "stonecut" -> "Stonecutting";
            case "smith" -> "Smithing";
            default -> "Crafting";
        };
    }

    private static String verb(RecipeModel r) {
        return switch (r.type()) {
            case "smelting", "blasting" -> "smelt";
            case "smoking", "campfire" -> "cook";
            case "stonecutting" -> "stonecut";
            case "smithing" -> "smith";
            default -> "craft";
        };
    }
}
