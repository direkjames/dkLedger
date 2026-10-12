package com.direk.dkledger.audit;

import com.direk.dkledger.audit.model.RecipeModel;

import java.util.List;
import java.util.Set;

/**
 * What the running server knows that config files don't: its recipes and registered commands.
 * Collected on the main thread, then handed to the audit.
 *
 * @param recipes  every recipe the server has
 * @param commands every command name and alias, lower case, with and without "plugin:" prefixes;
 *                 empty to skip the unknown-command check
 */
public record ServerFacts(List<RecipeModel> recipes, Set<String> commands) {

    public static ServerFacts none() {
        return new ServerFacts(List.of(), Set.of());
    }
}
