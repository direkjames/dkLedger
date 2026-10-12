package com.direk.dkledger.platform;

import com.direk.dkledger.audit.ServerFacts;
import com.direk.dkledger.audit.model.RecipeModel;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.ComplexRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Collects the server's recipes and commands. Must run on the main thread. */
public final class ServerFactsCollector {

    private ServerFactsCollector() {
    }

    public static ServerFacts collect(Server server) {
        List<RecipeModel> recipes = new ArrayList<>();
        Iterator<Recipe> it = server.recipeIterator();
        while (it.hasNext()) {
            try {
                RecipeModel model = convert(it.next());
                if (model != null) recipes.add(model);
            } catch (RuntimeException ignored) {
                // A plugin's odd custom recipe: skip it, the others still count.
            }
        }
        Set<String> commands = new java.util.HashSet<>();
        for (String label : server.getCommandMap().getKnownCommands().keySet()) {
            commands.add(label.toLowerCase(Locale.ROOT));
        }
        return new ServerFacts(List.copyOf(recipes), Set.copyOf(commands));
    }

    static RecipeModel convert(Recipe recipe) {
        if (recipe instanceof ComplexRecipe) return null; // map cloning, firework stars...: no fixed ingredients
        ItemStack result = recipe.getResult();
        if (result.getType().isAir() || result.getAmount() <= 0) return null;
        String key = recipe instanceof Keyed keyed ? keyed.getKey().toString() : "unknown";
        String type;
        List<RecipeModel.Ingredient> ingredients = new ArrayList<>();

        if (recipe instanceof ShapedRecipe shaped) {
            type = "crafting";
            Map<Character, Integer> counts = new LinkedHashMap<>();
            for (String row : shaped.getShape()) {
                for (char c : row.toCharArray()) if (c != ' ') counts.merge(c, 1, Integer::sum);
            }
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            Map<List<String>, Integer> merged = new LinkedHashMap<>();
            for (Map.Entry<Character, Integer> e : counts.entrySet()) {
                List<String> materials = materials(choices.get(e.getKey()));
                if (!materials.isEmpty()) merged.merge(materials, e.getValue(), Integer::sum);
            }
            merged.forEach((m, n) -> ingredients.add(new RecipeModel.Ingredient(m, n)));
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            type = "crafting";
            Map<List<String>, Integer> merged = new LinkedHashMap<>();
            for (RecipeChoice choice : shapeless.getChoiceList()) {
                List<String> materials = materials(choice);
                if (!materials.isEmpty()) merged.merge(materials, 1, Integer::sum);
            }
            merged.forEach((m, n) -> ingredients.add(new RecipeModel.Ingredient(m, n)));
        } else if (recipe instanceof CookingRecipe<?> cooking) {
            type = cooking instanceof FurnaceRecipe ? "smelting"
                    : cooking instanceof BlastingRecipe ? "blasting"
                    : cooking instanceof SmokingRecipe ? "smoking"
                    : cooking instanceof CampfireRecipe ? "campfire" : "smelting";
            add(ingredients, cooking.getInputChoice());
        } else if (recipe instanceof StonecuttingRecipe stonecutting) {
            type = "stonecutting";
            add(ingredients, stonecutting.getInputChoice());
        } else if (recipe instanceof SmithingTransformRecipe smithing) {
            type = "smithing";
            add(ingredients, smithing.getTemplate());
            add(ingredients, smithing.getBase());
            add(ingredients, smithing.getAddition());
        } else {
            return null;
        }
        if (ingredients.isEmpty()) return null;
        return new RecipeModel(key, type, result.getType().name(), result.getAmount(), List.copyOf(ingredients));
    }

    private static void add(List<RecipeModel.Ingredient> list, RecipeChoice choice) {
        List<String> materials = materials(choice);
        if (!materials.isEmpty()) list.add(new RecipeModel.Ingredient(materials, 1));
    }

    private static List<String> materials(RecipeChoice choice) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (choice instanceof RecipeChoice.MaterialChoice m) {
            for (Material material : m.getChoices()) if (!material.isAir()) out.add(material.name());
        } else if (choice instanceof RecipeChoice.ExactChoice e) {
            for (ItemStack item : e.getChoices()) if (!item.getType().isAir()) out.add(item.getType().name());
        }
        return List.copyOf(out);
    }
}
