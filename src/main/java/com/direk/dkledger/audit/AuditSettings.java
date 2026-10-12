package com.direk.dkledger.audit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the config audit can be tuned with. The plugin fills it from config.yml;
 * {@link #defaults(Path)} gives the same defaults config.yml ships with.
 */
public final class AuditSettings {

    /** The server's plugins folder. */
    public Path pluginsFolder;
    /** Where the last scan's prices are saved for A12, or null to skip A12. */
    public Path snapshotFile;

    // ------------------------------------------------------------- where each plugin keeps its files
    // Each is a list of places to try, relative to the plugins folder. The first one that exists is used.
    public List<String> esguiShops = List.of("EconomyShopGUI-Premium/shops", "EconomyShopGUI/shops");
    public List<String> lodestockFolder = List.of("Lodestock");
    public List<String> virtualSpawnerFolder = List.of("VirtualSpawner", "Virtual Spawner");
    public List<String> headDatabaseConfig = List.of("HeadDatabase/config.yml");
    public List<String> dkBankFolder = List.of("dkBank");
    public List<String> afkZoneConfig = List.of("AxAFKZone/config.yml", "AxAfkZone/config.yml");
    public List<String> nDailyRewardsFolder = List.of("NDailyRewards");
    public List<String> rewardsLiteFolder = List.of("RewardsLite/rewards");
    public List<String> oDailyQuestsFolder = List.of("ODailyQuests");
    public List<String> pinataPartyConfig = List.of("PinataParty/config.yml");
    public List<String> aOneBlockFolder = List.of("BentoBox/addons/AOneBlock");
    public List<String> bentoBoxAddonsFolder = List.of("BentoBox/addons");
    public List<String> playerWarpsConfig = List.of("AxPlayerWarps/config.yml");
    public List<String> auctionsCurrencies = List.of("AxAuctions/currencies.yml", "AxAuction/currencies.yml");
    public List<String> crateKeysFolder = List.of("ExcellentCrates/keys");

    // ------------------------------------------------------------- the reward command scan (A7, A8)
    /** Files skipped by the scan of all plugin folders (globs, relative to the plugins folder). */
    public List<String> scanSkip = new ArrayList<>(List.of(
            "dkLedger/**", "**/locales/**", "**/lang/**", "**/languages/**", "**/translations/**",
            "**/userdata/**", "**/playerdata/**", "**/players/**", "**/database/**", "**/logs/**",
            "**/backups/**", "**/cache/**", "**/yaml-storage/**"));
    /** Bigger files are skipped by the scan (they're data, not config). */
    public int scanMaxFileKb = 2048;
    /** Command names never reported as unknown. */
    public List<String> ignoreCommands = new ArrayList<>();
    /** "file:key" prefixes never checked for commands, e.g. "HeadDatabase/config.yml:custom-command". */
    public List<String> ignoreCommandPaths = new ArrayList<>(List.of("HeadDatabase/config.yml:custom-command"));

    // ------------------------------------------------------------- thresholds
    /** A2/A3: a loop is reported when it makes at least this much per item or craft. */
    public double minLoopProfit = 0.05;
    /** A4: report when crafting makes items worth at least this many times their inputs... */
    public double valueLeapRatio = 2.0;
    /** ...and gains at least this much per craft. */
    public double valueLeapMinGain = 1.0;
    /** A4: report items that can only be sold after crafting when one craft is worth at least this. */
    public double sellableOnlyAfterCraftingMin = 3.0;
    /** A5: highest fair sell price for items players can produce forever. */
    public Map<String, Double> renewableMaxSell = new LinkedHashMap<>(defaultRenewables());
    /** A6: warn when a spawner pays itself back faster than this many hours of standing next to it. */
    public double spawnerMinPaybackHours = 240;
    /** A6: warn when a spawner drop sells this many times higher outside Virtual Spawner. */
    public double spawnerDropElsewhereRatio = 1.5;
    /** A7: warn when one faucet is more than this share of a player's daily income. */
    public double faucetShareWarn = 0.40;
    /** A9: warn when a bank tier pays more than this per online hour at its cap. */
    public double bankOnlinePerHourWarn = 1000;
    /** A10: report OneBlock fixed blocks worth at least this. */
    public double oneBlockFixedValue = 50;
    /** A10: report chest loot worth at least this per item stack. */
    public double oneBlockChestValue = 500;
    /** A10: warn when a phase pays more than this per block broken, on average. */
    public double oneBlockPhaseAverageWarn = 3.0;
    /** Most findings of one kind listed separately; the rest are summed up in one line. */
    public int maxFindingsPerCheck = 25;

    // ------------------------------------------------------------- player profiles for the daily income estimate (A7)
    public Profile casual = new Profile(2, 0, 4);
    public Profile grinder = new Profile(6, 8, 4);
    /** Playtime milestones are spread over this many days to give a "per day" number. */
    public int milestoneDays = 30;

    // ------------------------------------------------------------- assumptions about plugins whose code we can't see
    /** Lodestock: players buy at the base price and sell at base price minus the tax. */
    public boolean lodestockTaxOnSell = true;
    /** AxAFKZone: "weighted" picks one reward per roll by chance; "independent" rolls every reward. */
    public String afkZoneRollMode = "weighted";

    /**
     * How a typical player of one kind spends a day.
     *
     * @param activeHours hours playing (not AFK)
     * @param afkHours    hours AFK online (AFK zone, spawners)
     * @param votes       votes per day
     */
    public record Profile(double activeHours, double afkHours, int votes) {
    }

    // ------------------------------------------------------------- "what if" changes (never saved to any file)
    /** Runs after every plugin's files are read and before the checks: changes prices, spawners... */
    public java.util.function.Consumer<AuditContext> mutator;
    /** Bank tier overrides: tier id -> {online rate, offline rate, cap}; NaN keeps the value from tiers.yml. */
    public Map<String, double[]> bankOverrides = new LinkedHashMap<>();
    /** Reward multipliers by faucet name, e.g. "afk zone" -> 0.5. Names are compared without case, spaces or _. */
    public Map<String, Double> faucetScale = new LinkedHashMap<>();

    /** A copy for a simulation: same settings, no price snapshot (a simulation must not change A12's file). */
    public AuditSettings copyForSimulation() {
        AuditSettings c = new AuditSettings();
        for (java.lang.reflect.Field f : AuditSettings.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.set(c, f.get(this));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        c.snapshotFile = null;
        c.maxFindingsPerCheck = Integer.MAX_VALUE; // compare full lists, so the list cap can't make findings look new
        c.mutator = null;
        c.bankOverrides = new LinkedHashMap<>();
        c.faucetScale = new LinkedHashMap<>();
        return c;
    }

    /** Lower case without spaces, "_" or "-": "AFK zone" and "afk_zone" match. */
    public static String key(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
    }

    public static AuditSettings defaults(Path pluginsFolder) {
        AuditSettings s = new AuditSettings();
        s.pluginsFolder = pluginsFolder;
        return s;
    }

    /** Items players can make forever with a farm or generator, and their highest fair sell price. */
    public static Map<String, Double> defaultRenewables() {
        Map<String, Double> m = new LinkedHashMap<>();
        // Generators
        m.put("COBBLESTONE", 0.25);
        m.put("STONE", 0.25);
        m.put("COBBLED_DEEPSLATE", 0.25);
        m.put("BASALT", 0.25);
        m.put("SMOOTH_BASALT", 0.3);
        m.put("POLISHED_BASALT", 0.3);
        m.put("OBSIDIAN", 8.0);
        // Automatic farms
        m.put("CACTUS", 0.1);
        m.put("GREEN_DYE", 0.1);
        m.put("BAMBOO", 0.1);
        m.put("KELP", 0.1);
        m.put("DRIED_KELP", 0.1);
        m.put("SUGAR_CANE", 0.1);
        m.put("SUGAR", 0.1);
        m.put("PAPER", 0.1);
        m.put("MOSS_BLOCK", 0.3);
        m.put("VINE", 0.5);
        m.put("POINTED_DRIPSTONE", 0.3);
        m.put("SNOWBALL", 0.25);
        m.put("ICE", 1.0);
        m.put("PUMPKIN", 1.5);
        m.put("MELON_SLICE", 0.3);
        m.put("WHITE_WOOL", 1.5);
        m.put("STRING", 2.0);
        m.put("BONE_MEAL", 0.6);
        return m;
    }
}
