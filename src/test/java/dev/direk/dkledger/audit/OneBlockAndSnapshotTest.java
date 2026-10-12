package dev.direk.dkledger.audit;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static dev.direk.dkledger.audit.TestPlugins.has;
import static dev.direk.dkledger.audit.TestPlugins.map;
import static dev.direk.dkledger.audit.TestPlugins.shop;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneBlockAndSnapshotTest {

    private static TestPlugins oneBlock(int resetLimit) throws Exception {
        return new TestPlugins()
                .put("EconomyShopGUI-Premium/shops/Misc.yml", shop("SPONGE", 525.0, 131.25, "CRYING_OBSIDIAN", 62.5, 15.63,
                        "ENCHANTED_GOLDEN_APPLE", -1, 2500.0))
                .put("BentoBox/addons/AOneBlock/config.yml", map("island", map("reset", map("reset-limit", resetLimit))))
                .put("BentoBox/addons/AOneBlock/phases_index.yml", map("phases", List.of(
                        map("file", "0_plains", "section", "0", "name", "Plains", "length", 700),
                        map("file", "11500_the end", "section", "11500", "name", "The End", "length", 500)), "gotoAtEnd", 11500))
                .put("BentoBox/addons/AOneBlock/phases/0_plains.yml", map("0", map("name", "Plains",
                        "fixedBlocks", map(50, "SPONGE"), "blocks", map("GRASS_BLOCK", 100))))
                .put("BentoBox/addons/AOneBlock/phases/11500_the end.yml", map("11500", map("name", "The End",
                        "blocks", map("CRYING_OBSIDIAN", 50, "END_STONE", 50))))
                .put("BentoBox/addons/AOneBlock/phases/11500_the end_chests.yml", map("11500", map("chests", map(
                        1, map("rarity", "EPIC", "contents", map(13, map("type", "ENCHANTED_GOLDEN_APPLE", "amount", 1)))))));
    }

    @Test
    void unlimitedResetsWithAValuableFixedBlockIsCritical() throws Exception {
        AuditResult r = oneBlock(-1).audit(ServerFacts.none());
        assertTrue(has(r, "A10", Severity.CRITICAL, "Plains always gives SPONGE"));
        assertTrue(has(r, "A10", Severity.WARNING, "The End pays $7.8"));
        assertTrue(has(r, "A10", Severity.INFO, "The End chests"));
    }

    @Test
    void limitedResetsOnlyInform() throws Exception {
        AuditResult r = oneBlock(2).audit(ServerFacts.none());
        assertTrue(has(r, "A10", Severity.INFO, "Plains always gives SPONGE"));
    }

    @Test
    void priceChangesShowOnTheNextScan() throws Exception {
        TestPlugins p = new TestPlugins().put("EconomyShopGUI-Premium/shops/Blocks.yml", shop("BASALT", 40.0, 10.0));
        AuditSettings settings = p.settings();
        settings.snapshotFile = Files.createTempDirectory("dkledger-snap").resolve("prices.tsv");
        AuditResult first = Auditor.run(settings, p, ServerFacts.none());
        assertTrue(has(first, "A12", Severity.INFO, "starting point"));

        p.put("EconomyShopGUI-Premium/shops/Blocks.yml", shop("BASALT", 40.0, 0.18));
        AuditResult second = Auditor.run(settings, p, ServerFacts.none());
        assertTrue(has(second, "A12", Severity.INFO, "BASALT in EconomyShopGUI: sell $10.00 → $0.18"));
    }
}
