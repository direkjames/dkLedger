package dev.direk.dkledger.config;

import dev.direk.dkledger.audit.AuditSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * dkLedger's config.yml.
 *
 * @param scanOnStartup  run a scan once the server has started
 * @param startupDelay   seconds to wait after startup, so every plugin has finished loading
 * @param keepReports    how many old reports to keep in plugins/dkLedger/reports
 * @param zone           time zone for report times
 * @param audit          everything the checks use
 */
public record LedgerConfig(boolean scanOnStartup, int startupDelay, int keepReports, ZoneId zone, AuditSettings audit) {

    public static LedgerConfig load(FileConfiguration c, Path pluginsFolder, Path dataFolder, Logger log) {
        AuditSettings a = AuditSettings.defaults(pluginsFolder);
        a.snapshotFile = dataFolder.resolve("data").resolve("price-snapshot.tsv");

        a.esguiShops = paths(c, "plugins.economyshopgui-shops", a.esguiShops);
        a.lodestockFolder = paths(c, "plugins.lodestock", a.lodestockFolder);
        a.virtualSpawnerFolder = paths(c, "plugins.virtual-spawner", a.virtualSpawnerFolder);
        a.headDatabaseConfig = paths(c, "plugins.head-database", a.headDatabaseConfig);
        a.dkBankFolder = paths(c, "plugins.dkbank", a.dkBankFolder);
        a.afkZoneConfig = paths(c, "plugins.afk-zone", a.afkZoneConfig);
        a.nDailyRewardsFolder = paths(c, "plugins.daily-rewards", a.nDailyRewardsFolder);
        a.rewardsLiteFolder = paths(c, "plugins.playtime-rewards", a.rewardsLiteFolder);
        a.oDailyQuestsFolder = paths(c, "plugins.daily-quests", a.oDailyQuestsFolder);
        a.pinataPartyConfig = paths(c, "plugins.pinata-party", a.pinataPartyConfig);
        a.aOneBlockFolder = paths(c, "plugins.oneblock", a.aOneBlockFolder);
        a.bentoBoxAddonsFolder = paths(c, "plugins.bentobox-addons", a.bentoBoxAddonsFolder);
        a.playerWarpsConfig = paths(c, "plugins.player-warps", a.playerWarpsConfig);
        a.auctionsCurrencies = paths(c, "plugins.auctions", a.auctionsCurrencies);
        a.crateKeysFolder = paths(c, "plugins.crate-keys", a.crateKeysFolder);

        a.scanSkip = paths(c, "checks.scan.skip", a.scanSkip);
        a.scanMaxFileKb = c.getInt("checks.scan.max-file-kb", a.scanMaxFileKb);
        a.ignoreCommands = paths(c, "checks.commands.ignore", a.ignoreCommands);
        a.ignoreCommandPaths = paths(c, "checks.commands.ignore-paths", a.ignoreCommandPaths);

        a.maxFindingsPerCheck = c.getInt("checks.max-findings-per-check", a.maxFindingsPerCheck);
        a.minLoopProfit = c.getDouble("checks.loops.min-profit", a.minLoopProfit);
        a.valueLeapRatio = c.getDouble("checks.crafting.value-ratio", a.valueLeapRatio);
        a.valueLeapMinGain = c.getDouble("checks.crafting.min-gain", a.valueLeapMinGain);
        a.sellableOnlyAfterCraftingMin = c.getDouble("checks.crafting.sellable-only-after-crafting-min", a.sellableOnlyAfterCraftingMin);
        ConfigurationSection renewables = c.getConfigurationSection("checks.renewables");
        if (renewables != null) {
            Map<String, Double> map = new LinkedHashMap<>();
            for (String key : renewables.getKeys(false)) map.put(key.toUpperCase(Locale.ROOT), renewables.getDouble(key));
            a.renewableMaxSell = map;
        }
        a.spawnerMinPaybackHours = c.getDouble("checks.spawners.min-payback-hours", a.spawnerMinPaybackHours);
        a.spawnerDropElsewhereRatio = c.getDouble("checks.spawners.drop-elsewhere-ratio", a.spawnerDropElsewhereRatio);
        a.faucetShareWarn = c.getDouble("checks.rewards.faucet-share-warn", a.faucetShareWarn);
        a.milestoneDays = c.getInt("checks.rewards.milestone-days", a.milestoneDays);
        a.afkZoneRollMode = c.getString("checks.rewards.afk-zone-roll-mode", a.afkZoneRollMode);
        a.bankOnlinePerHourWarn = c.getDouble("checks.bank.online-per-hour-warn", a.bankOnlinePerHourWarn);
        a.oneBlockFixedValue = c.getDouble("checks.oneblock.fixed-block-value", a.oneBlockFixedValue);
        a.oneBlockChestValue = c.getDouble("checks.oneblock.chest-item-value", a.oneBlockChestValue);
        a.oneBlockPhaseAverageWarn = c.getDouble("checks.oneblock.phase-average-warn", a.oneBlockPhaseAverageWarn);
        a.lodestockTaxOnSell = c.getBoolean("checks.lodestock-tax-on-sell", a.lodestockTaxOnSell);

        a.casual = profile(c, "profiles.casual", a.casual);
        a.grinder = profile(c, "profiles.grinder", a.grinder);

        ZoneId zone;
        String zoneText = c.getString("scan.timezone", "");
        try {
            zone = zoneText == null || zoneText.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zoneText);
        } catch (RuntimeException e) {
            log.warning("scan.timezone '" + zoneText + "' isn't a time zone (e.g. Asia/Manila). Using the server's.");
            zone = ZoneId.systemDefault();
        }
        return new LedgerConfig(c.getBoolean("scan.on-startup", true), Math.max(0, c.getInt("scan.startup-delay-seconds", 30)),
                Math.max(1, c.getInt("scan.keep-reports", 20)), zone, a);
    }

    private static List<String> paths(FileConfiguration c, String key, List<String> fallback) {
        if (!c.contains(key)) return fallback;
        if (c.isList(key)) return List.copyOf(c.getStringList(key));
        String single = c.getString(key);
        return single == null || single.isBlank() ? fallback : List.of(single);
    }

    private static AuditSettings.Profile profile(FileConfiguration c, String key, AuditSettings.Profile fallback) {
        return new AuditSettings.Profile(c.getDouble(key + ".active-hours", fallback.activeHours()),
                c.getDouble(key + ".afk-hours", fallback.afkHours()), c.getInt(key + ".votes", fallback.votes()));
    }
}
