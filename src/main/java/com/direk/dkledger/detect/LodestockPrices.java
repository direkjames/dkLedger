package com.direk.dkledger.detect;

import io.github.direkjames.lodestock.api.LodestockApi;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads Lodestock's live prices for the price index (B8). Only loaded when Lodestock is enabled. */
public final class LodestockPrices {

    private LodestockPrices() {
    }

    /** Every market item's current price, or empty if Lodestock isn't running. */
    public static Map<String, Double> now() {
        Map<String, Double> out = new LinkedHashMap<>();
        LodestockApi.find().ifPresent(api -> {
            for (String id : api.itemIds()) api.item(id).ifPresent(info -> out.put(id, info.price()));
        });
        return out;
    }
}
