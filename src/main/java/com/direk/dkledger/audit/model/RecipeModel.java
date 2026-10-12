package com.direk.dkledger.audit.model;

import java.util.List;

/**
 * A crafting, smelting, stonecutter or smithing recipe, without any Bukkit types.
 *
 * @param key         the recipe's namespaced key
 * @param type        "crafting", "smelting", "stonecutting", "smithing"...
 * @param result      the result material
 * @param amount      how many the recipe makes
 * @param ingredients what it takes
 */
public record RecipeModel(String key, String type, String result, int amount, List<Ingredient> ingredients) {

    /**
     * One ingredient slot type: any of {@code choices} works, and the recipe needs {@code count} of them.
     */
    public record Ingredient(List<String> choices, int count) {
    }
}
