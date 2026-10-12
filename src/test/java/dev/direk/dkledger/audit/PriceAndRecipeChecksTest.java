package dev.direk.dkledger.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.direk.dkledger.audit.TestPlugins.has;
import static dev.direk.dkledger.audit.TestPlugins.map;
import static dev.direk.dkledger.audit.TestPlugins.recipe;
import static dev.direk.dkledger.audit.TestPlugins.server;
import static dev.direk.dkledger.audit.TestPlugins.shop;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceAndRecipeChecksTest {

    @Test
    void sellAboveBuyIsCritical() throws Exception {
        TestPlugins p = new TestPlugins().put("EconomyShopGUI-Premium/shops/Dyes.yml",
                shop("GREEN_DYE", 0.11, 0.4, "RED_DYE", 1.05, 0.4, "OXIDIZED_CUT_COPPER_SLAB", 0.01, 0.01));
        AuditResult r = p.audit(ServerFacts.none());
        assertTrue(has(r, "A1", Severity.CRITICAL, "GREEN_DYE"));
        assertTrue(has(r, "A1", Severity.WARNING, "OXIDIZED_CUT_COPPER_SLAB"));
        assertFalse(has(r, "A1", Severity.CRITICAL, "RED_DYE"));
    }

    @Test
    void buyingInLodestockAndSellingInTheShopIsCritical() throws Exception {
        TestPlugins p = new TestPlugins()
                .put("EconomyShopGUI-Premium/shops/Misc.yml", shop("NETHERITE_SCRAP", 555.45, 138.87))
                .put("Lodestock/config.yml", map("tax-percent", 15.0))
                .put("Lodestock/items.yml", map("netherite_scrap", map("base-price", 75.0), "iron_ingot", map("base-price", 4.0)));
        AuditResult r = p.audit(ServerFacts.none());
        assertTrue(has(r, "A2", Severity.CRITICAL, "NETHERITE_SCRAP: buy in Lodestock, sell in EconomyShopGUI"));
    }

    @Test
    void findsBuyCraftSellLoopsAndValueLeaps() throws Exception {
        TestPlugins p = new TestPlugins().put("EconomyShopGUI-Premium/shops/All.yml", shop(
                "CACTUS", 0.1, 0.03, "GREEN_DYE", 2.0, 0.4,
                "WHITE_WOOL", 29.4, 1.5, "WHITE_CARPET", 20.58, 5.15,
                "OAK_LOG", 3.0, 0.75, "OAK_PLANKS", 1.11, 0.28,
                "WHITE_BANNER", 185.83, 46.46, "STICK", 0.58, 0.15,
                "LEATHER", 33.6, -1, "LEATHER_CHESTPLATE", 282.24, 70.56));
        List<dev.direk.dkledger.audit.model.RecipeModel> recipes = List.of(
                recipe("smelting", "GREEN_DYE", 1, 1, "CACTUS"),
                recipe("crafting", "WHITE_CARPET", 3, 2, "WHITE_WOOL"),
                recipe("crafting", "OAK_PLANKS", 4, 1, "OAK_LOG|SPRUCE_LOG"),
                recipe("crafting", "LEATHER_CHESTPLATE", 1, 8, "LEATHER"),
                recipe("crafting", "LIME_WOOL", 1, 1, "LIME_DYE", 1, "WHITE_WOOL|LIME_WOOL"));
        AuditResult r = p.audit(server(recipes));
        assertTrue(has(r, "A3", Severity.CRITICAL, "GREEN_DYE"), "cactus bought for $0.10 smelts into $0.40 dye");
        assertTrue(has(r, "A4", Severity.WARNING, "WHITE_CARPET is worth"), "2 wool ($3) make carpets worth $15");
        assertTrue(has(r, "A4", Severity.WARNING, "LEATHER_CHESTPLATE sells for"), "unsellable leather becomes a sellable chestplate");
        assertFalse(has(r, "A4", Severity.WARNING, "OAK_PLANKS"), "planks are fairly priced");
    }

    @Test
    void renewablesSoldTooHigh() throws Exception {
        TestPlugins p = new TestPlugins().put("EconomyShopGUI-Premium/shops/Blocks.yml",
                shop("BASALT", 40.0, 10.0, "COBBLESTONE", 0.68, 0.17, "VINE", 7.0, 0.6));
        AuditResult r = p.audit(ServerFacts.none());
        assertTrue(has(r, "A5", Severity.CRITICAL, "BASALT"));
        assertTrue(has(r, "A5", Severity.WARNING, "VINE"));
        assertFalse(has(r, "A5", Severity.WARNING, "COBBLESTONE"));
    }
}
