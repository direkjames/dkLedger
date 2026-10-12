package com.direk.dkledger.config;

import com.direk.dkledger.audit.AuditSettings;
import com.direk.dkledger.detect.Thresholds;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything in dkLedger's config.yml.
 *
 * @param zone            time zone for days, reports and the weekly schedule
 * @param scanOnStartup   run the config audit once the server has started
 * @param startupDelay    seconds to wait after startup before that scan
 * @param keepReports     how many audit reports to keep
 * @param audit           the config audit's settings
 * @param ledger          ledger settings
 * @param thresholds      alert thresholds
 * @param alerts          alert delivery settings
 * @param freeze          what a freeze blocks
 * @param weekly          the weekly report
 * @param tracking        what's recorded besides money: item trades, playtime, progress
 * @param web             the web dashboard
 * @param warnings        problems found in config.yml (shown on startup and reload)
 */
public record LedgerConfig(ZoneId zone, boolean scanOnStartup, int startupDelay, int keepReports, AuditSettings audit,
                           Ledger ledger, Thresholds thresholds, Alerts alerts, Freeze freeze, Weekly weekly,
                           Tracking tracking, Web web, List<String> warnings) {

    /**
     * @param enabled          record money changes
     * @param flushSeconds     how often entries are saved
     * @param keepDays         how long single entries are kept (daily totals are kept forever)
     * @param snapshotMinutes  how often the money supply is counted (and the hourly checks run)
     * @param balancesPerTick  wallets read per tick while counting
     * @param transferSources  sources that move money between players
     * @param internalSources  sources that move money between a player's own wallet and bank
     */
    public record Ledger(boolean enabled, int flushSeconds, int keepDays, int snapshotMinutes, int balancesPerTick,
                         List<String> transferSources, List<String> internalSources) {
    }

    public record Alerts(long repeatMillis, boolean notifyInGame, String sound) {
    }

    public record Freeze(List<String> commands, List<String> sources) {
    }

    public record Weekly(boolean enabled, DayOfWeek day, LocalTime time, int keep) {
    }

    /**
     * @param items           record item trades with shops (Lodestock, EconomyShopGUI, and plugins using the API)
     * @param playtime        record first joins and minutes played (active and AFK) per day
     * @param afkAfterMinutes without EssentialsX's AFK status, a player idle this long counts as AFK (0 = never)
     * @param progress        record progress events (OneBlock phases, dkBank tiers, and plugins using the API)
     */
    public record Tracking(boolean items, boolean playtime, int afkAfterMinutes, boolean progress) {
    }

    /**
     * @param enabled      run the dashboard
     * @param bind         the address to listen on (0.0.0.0 = every address; right for Pterodactyl)
     * @param port         the port (in Pterodactyl, an allocation given to this server)
     * @param publicUrl    the address people open, used in login links ("https://ledger.example.com")
     * @param sessionHours how long a sign-in lasts
     * @param trustProxy   behind Cloudflare or a reverse proxy: read the visitor's address from its headers
     */
    public record Web(boolean enabled, String bind, int port, String publicUrl, int sessionHours, boolean trustProxy) {
    }

    public static LedgerConfig load(YamlConfiguration c, Path pluginsFolder, Path dataFolder) {
        List<String> warnings = new ArrayList<>();
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

        ZoneId zone = ZoneId.systemDefault();
        String zoneText = c.getString("timezone", "");
        if (zoneText != null && !zoneText.isBlank()) {
            try {
                zone = ZoneId.of(zoneText.trim());
            } catch (RuntimeException e) {
                warnings.add("timezone '" + zoneText + "' isn't a time zone (example: Asia/Manila). Using the server's.");
            }
        }

        Ledger ledger = new Ledger(c.getBoolean("ledger.enabled", true), Math.max(1, c.getInt("ledger.flush-seconds", 5)),
                Math.max(1, c.getInt("ledger.keep-entries-days", 90)), Math.max(5, c.getInt("ledger.snapshot-minutes", 60)),
                Math.max(1, c.getInt("ledger.balances-per-tick", 20)),
                paths(c, "ledger.transfer-sources", List.of("Essentials /pay", "AxTrade", "AxAuctions", "ChestShop", "dkBank transfers")),
                paths(c, "ledger.internal-sources", List.of("dkBank")));

        Thresholds d = Thresholds.defaults();
        Thresholds t = new Thresholds(
                c.getDouble("alerts.warmup-days", d.warmupDays()),
                c.getDouble("alerts.supply-growth.warning", d.supplyGrowthWarn()), c.getDouble("alerts.supply-growth.critical", d.supplyGrowthCrit()),
                c.getDouble("alerts.per-player-growth.warning", d.perPlayerGrowthWarn()), c.getDouble("alerts.per-player-growth.critical", d.perPlayerGrowthCrit()),
                c.getDouble("alerts.source-share.warning", d.sourceShareWarn()), c.getDouble("alerts.source-share.critical", d.sourceShareCrit()),
                c.getDouble("alerts.source-share.min-new-money", d.sourceShareMinNewMoney()),
                c.getDouble("alerts.sink-ratio.warning", d.sinkRatioWarn()), c.getDouble("alerts.sink-ratio.critical", d.sinkRatioCrit()),
                c.getDouble("alerts.player-vs-median.warning", d.medianWarn()), c.getDouble("alerts.player-vs-median.critical", d.medianCrit()),
                c.getDouble("alerts.player-vs-median.min-earned", d.medianMinEarned()), c.getInt("alerts.player-vs-median.min-earners", d.medianMinEarners()),
                c.getDouble("alerts.player-per-hour.warning", d.perHourWarn()), c.getDouble("alerts.player-per-hour.critical", d.perHourCrit()),
                c.getInt("alerts.buy-sell-loop.warning", d.loopWarn()), c.getInt("alerts.buy-sell-loop.critical", d.loopCrit()),
                c.getInt("alerts.buy-sell-loop.window-minutes", (int) (d.loopWindowMillis() / 60_000)) * 60_000L,
                c.getBoolean("alerts.new-source", d.newSource()),
                c.getDouble("alerts.staff-money.warning", d.staffMoneyWarn()),
                c.getDouble("alerts.top-share.warning", d.topShareWarn()), c.getDouble("alerts.top-share.critical", d.topShareCrit()),
                c.getInt("alerts.top-share.min-accounts", d.topShareMinAccounts()),
                c.getDouble("alerts.price-change.warning", d.priceChangeWarn()), c.getDouble("alerts.price-change.critical", d.priceChangeCrit()),
                c.getInt("alerts.price-change.days", d.priceChangeDays()),
                c.getDouble("alerts.alt-transfers.warning", d.altTransferWarn()), c.getDouble("alerts.alt-transfers.critical", d.altTransferCrit()),
                c.getDouble("alerts.big-transfer.warning", d.bigTransferWarn()), c.getDouble("alerts.big-transfer.critical", d.bigTransferCrit()));
        Alerts alerts = new Alerts((long) (Math.max(0.1, c.getDouble("alerts.repeat-hours", 6)) * 3_600_000L),
                c.getBoolean("alerts.notify-in-game", true), c.getString("alerts.sound", "block.note_block.bell"));

        Freeze freeze = new Freeze(paths(c, "freeze.blocked-commands", List.of("pay", "epay", "trade", "ah sell", "auction sell", "bank pay", "bank transfer")),
                paths(c, "freeze.blocked-sources", List.of("AxTrade", "AxAuctions", "ChestShop")));

        DayOfWeek day = DayOfWeek.MONDAY;
        try {
            day = DayOfWeek.valueOf(c.getString("weekly-report.day", "MONDAY").trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            warnings.add("weekly-report.day must be MONDAY to SUNDAY. Using MONDAY.");
        }
        LocalTime time = LocalTime.of(9, 0);
        try {
            time = LocalTime.parse(c.getString("weekly-report.time", "09:00").trim());
        } catch (RuntimeException e) {
            warnings.add("weekly-report.time must look like 09:00. Using 09:00.");
        }
        Weekly weekly = new Weekly(c.getBoolean("weekly-report.enabled", true), day, time, Math.max(1, c.getInt("weekly-report.keep", 12)));

        Tracking tracking = new Tracking(c.getBoolean("tracking.items", true), c.getBoolean("tracking.playtime", true),
                Math.max(0, c.getInt("tracking.afk-after-minutes", 5)), c.getBoolean("tracking.progress", true));
        if (ledger.keepDays() < 60) {
            warnings.add("ledger.keep-entries-days is " + ledger.keepDays() + ": single entries and item trades older than that can't be "
                    + "looked into or undone. 90 or more is recommended.");
        }

        int port = c.getInt("web.port", 25580);
        if (port < 1 || port > 65535) {
            warnings.add("web.port must be 1 to 65535. Using 25580.");
            port = 25580;
        }
        String url = c.getString("web.public-url", "");
        if (url != null && !url.isBlank() && !url.strip().matches("(?i)https?://.+")) {
            warnings.add("web.public-url must start with http:// or https:// (example: http://ledger.example.com:25580).");
            url = "";
        }
        Web web = new Web(c.getBoolean("web.enabled", false), c.getString("web.bind", "0.0.0.0"), port, url == null ? "" : url,
                Math.max(1, c.getInt("web.session-hours", 12)), c.getBoolean("web.trust-proxy", false));

        return new LedgerConfig(zone, c.getBoolean("scan.on-startup", true), Math.max(0, c.getInt("scan.startup-delay-seconds", 30)),
                Math.max(1, c.getInt("scan.keep-reports", 20)), a, ledger, t, alerts, freeze, weekly, tracking, web, List.copyOf(warnings));
    }

    private static List<String> paths(YamlConfiguration c, String key, List<String> fallback) {
        if (!c.contains(key)) return fallback;
        if (c.isList(key)) return List.copyOf(c.getStringList(key));
        String single = c.getString(key);
        return single == null || single.isBlank() ? fallback : List.of(single);
    }

    private static AuditSettings.Profile profile(YamlConfiguration c, String key, AuditSettings.Profile fallback) {
        return new AuditSettings.Profile(c.getDouble(key + ".active-hours", fallback.activeHours()),
                c.getDouble(key + ".afk-hours", fallback.afkHours()), c.getInt(key + ".votes", fallback.votes()));
    }
}
