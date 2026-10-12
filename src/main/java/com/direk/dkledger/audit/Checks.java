package com.direk.dkledger.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/** The ids and names of all config checks, in report order. */
public final class Checks {

    public static final Map<String, String> NAMES;

    static {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("A1", "Sell price at or above buy price");
        names.put("A2", "Buy in one plugin, sell in another");
        names.put("A3", "Buy, craft and sell loops");
        names.put("A4", "Crafting multiplies value");
        names.put("A5", "Renewable items sold too high");
        names.put("A6", "Spawners");
        names.put("A7", "Rewards and daily income");
        names.put("A8", "Broken reward commands");
        names.put("A9", "Bank interest");
        names.put("A10", "OneBlock phases and resets");
        names.put("A11", "Money sinks");
        names.put("A12", "Price changes since last scan");
        NAMES = Map.copyOf(names);
    }

    private Checks() {
    }

    public static String name(String id) {
        return NAMES.getOrDefault(id, id);
    }

    /** "A3", "A10"... in report order. */
    public static java.util.List<String> ids() {
        return java.util.List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10", "A11", "A12");
    }
}
