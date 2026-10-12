package com.direk.dkledger.audit.model;

/**
 * One price for one item in one plugin. A price of 0 or less means "not possible" (not buyable or not sellable).
 *
 * @param source   the plugin, e.g. "EconomyShopGUI"
 * @param material the Bukkit material name, upper case
 * @param buy      what a player pays to buy one
 * @param sell     what a player gets for selling one
 * @param where    file and item id, for the report
 */
public record PriceEntry(String source, String material, double buy, double sell, String where) {

    public boolean buyable() {
        return buy > 0;
    }

    public boolean sellable() {
        return sell > 0;
    }
}
