package com.direk.dkledger.audit;

import com.direk.dkledger.audit.model.Faucet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.direk.dkledger.audit.TestPlugins.has;
import static com.direk.dkledger.audit.TestPlugins.map;
import static com.direk.dkledger.audit.TestPlugins.server;
import static com.direk.dkledger.audit.TestPlugins.shop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardAndBankChecksTest {

    private static Faucet faucet(AuditResult r, String name) {
        return r.faucets().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void dailyLoginIsAveragedOverTheCycle() throws Exception {
        TestPlugins p = new TestPlugins().put("NDailyRewards/config.yml", map("rewards", map("days", map(
                1, map("actions", List.of("[console] eco give <player> 200", "[message] hi")),
                2, map("actions", List.of("[console] eco give <player> 600", "[luck] {50} eco give <player> 200"))))));
        AuditResult r = p.audit(ServerFacts.none());
        assertEquals((200 + 600 + 100) / 2.0, faucet(r, "Daily login").casual(), 0.001);
    }

    @Test
    void afkZoneUsesWeightedRolls() throws Exception {
        TestPlugins p = new TestPlugins()
                .put("EconomyShopGUI-Premium/shops/Ores.yml", shop("DIAMOND", 100.0, 40.0))
                .put("AxAFKZone/config.yml", map("reward-time-seconds", 600, "roll-amount", 1, "rewards", List.of(
                        map("chance", 50.0, "commands", List.of("eco give %player% 100")),
                        map("chance", 50.0, "items", List.of(map("material", "diamond", "amount", 1))))));
        AuditResult r = p.audit(ServerFacts.none());
        // Per roll: half $100, half one $40 diamond = $70. Six rolls an hour, 8 AFK hours for a grinder.
        assertEquals(70 * 6 * 8, faucet(r, "AFK zone").grinder(), 0.001);
        assertEquals(0, faucet(r, "AFK zone").casual(), 0.001);
    }

    @Test
    void bankTierAtItsCap() throws Exception {
        TestPlugins p = new TestPlugins()
                .put("dkBank/config.yml", map("interest", map("online", map("period", "1h"), "offline", map("period", "1d")),
                        "alt-limit", map("enabled", true)))
                .put("dkBank/tiers.yml", map("tiers", map(
                        "basic", map("upgrade-cost", 0, "interest", map("online-rate", 0.15, "offline-rate", 0.1, "cap", 10000)),
                        "diamond", map("upgrade-cost", 750000, "interest", map("online-rate", 0.35, "offline-rate", 0.25, "cap", 400000)),
                        "nocap", map("buyable", false, "interest", map("online-rate", 0.1, "offline-rate", 0.1)))));
        AuditResult r = p.audit(ServerFacts.none());
        assertTrue(has(r, "A9", Severity.WARNING, "diamond pays $1,400 per online hour"));
        assertTrue(has(r, "A9", Severity.CRITICAL, "nocap has no interest cap"));
        assertFalse(has(r, "A9", Severity.WARNING, "alt limit"));
        // Casual: basic, 2h online ($15/h) + 22/24 of a day offline ($10/day).
        assertEquals(2 * 15 + 10 * 22 / 24.0, faucet(r, "Bank interest").casual(), 0.001);
    }

    @Test
    void unlimitedQuestRerollsAndBigFaucets() throws Exception {
        TestPlugins p = new TestPlugins()
                .put("ODailyQuests/config.yml", map("quests_per_category", map("global", 3), "reroll_maximum", -1,
                        "global_reward", map("enabled", true, "commands", List.of("eco give %player_name% 450"))))
                .put("ODailyQuests/quests/global.yml", map("quests", map(
                        1, map("reward", map("reward_type", "COMMAND", "commands", List.of("eco give %player_name% 100"))),
                        2, map("reward", map("reward_type", "MONEY", "amount", 500)))));
        AuditResult r = p.audit(ServerFacts.none());
        Faucet quests = faucet(r, "Daily quests");
        assertEquals(3 * 300 + 450, quests.casual(), 0.001);
        assertEquals(3 * 500 + 450, quests.grinder(), 0.001);
        assertTrue(has(r, "A7", Severity.WARNING, "rerolled without limit"));
        assertTrue(has(r, "A7", Severity.WARNING, "Daily quests is 100%"), "the only faucet is 100% of income");
    }

    @Test
    void unknownCommandsAndCrateKeys() throws Exception {
        TestPlugins p = new TestPlugins()
                .put("NDailyRewards/config.yml", map("rewards", map("days", map(7, map("actions", List.of(
                        "[console] florist give <player> 1", "[console] floristcoin give <player> 1", "[message] &aWeek done!"))))))
                .put("AxAFKZone/config.yml", map("rewards", List.of(map("chance", 2.0, "commands", List.of("crates key give %player% vote_key 1")))))
                .put("AxPlayerWarps/config.yml", map("main-command-aliases", List.of("pw", "pwarp")))
                .put("BentoBox/addons/AOneBlock/config.yml", map("aoneblock", map("command", map("count-command", "count"))))
                .put("ExcellentCrates/keys/vote.yml", map("name", "vote"));
        AuditResult r = p.audit(server(List.of(), "floristcoin", "crates", "eco"));
        assertTrue(has(r, "A8", Severity.WARNING, "Unknown command /florist in NDailyRewards"));
        assertFalse(has(r, "A8", Severity.WARNING, "/floristcoin"));
        assertFalse(has(r, "A8", Severity.WARNING, "/pw"), "aliases are names, not commands to run");
        assertFalse(has(r, "A8", Severity.WARNING, "/count"), "a *-command setting names a command");
        assertTrue(has(r, "A8", Severity.WARNING, "Crate key \"vote_key\" doesn't exist"));
    }
}
